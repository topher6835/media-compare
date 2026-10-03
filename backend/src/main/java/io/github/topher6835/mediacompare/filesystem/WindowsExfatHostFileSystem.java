package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Disabled protected-access boundary; never establishes persisted physical identity. */
public final class WindowsExfatHostFileSystem implements HostFileSystem {
    private final WindowsExfatNativeAccess nativeAccess;
    private final WindowsVolumeProbe volumes;
    private final WindowsExfatSupport support;

    public WindowsExfatHostFileSystem() {
        this(new WindowsExfatNative(), new WindowsVolumeProbe(), WindowsExfatSupport.PRODUCTION);
    }

    WindowsExfatHostFileSystem(WindowsExfatNativeAccess nativeAccess, WindowsVolumeProbe volumes,
            WindowsExfatSupport support) {
        this.nativeAccess = nativeAccess;
        this.volumes = volumes;
        this.support = support;
    }

    @Override public boolean isWindows() { return true; }
    @Override public String pathText(LocationPath location) { return new WindowsNtfsHostFileSystem().pathText(location); }
    @Override public FileSystemProfile profile(Path path) throws IOException { return volumes.probe(path).profile(); }
    @Override public boolean unsafeElement(Path path, BasicFileAttributes attributes) throws IOException {
        support.requireAvailable();
        // A pathname-only call does not hold the required chain, even when future support is enabled.
        return true;
    }
    @Override public HostFileCheck inspect(LocationPath path, long size, Long second, Integer nano) {
        return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
    }
    @Override public void requireSessionStorage(Path path) throws IOException {
        throw new WindowsFileAccessException(WindowsFileAccessException.Reason.UNSUPPORTED,
                "exFAT Session storage is unsupported");
    }

    public DirectoryChain openDirectoryChain(Path root) throws IOException {
        support.requireAvailable();
        if (!WindowsVolumeProbe.ordinaryDrivePath(root.toString()) || root.getRoot() == null) {
            throw unsupported("Protected roots require ordinary local drive paths");
        }
        if (root.getNameCount() > 256) throw uncertain("Directory chain exceeds depth bound");
        WindowsVolumeProbe.Result expected = requireVolume(root);
        var paths = new ArrayList<Path>();
        var leases = new ArrayList<WindowsExfatNativeAccess.Lease>();
        var observations = new ArrayList<WindowsExfatNativeAccess.Observation>();
        try {
            Path current = root.getRoot();
            acquireDirectory(current, expected, paths, leases, observations);
            for (Path component : root) {
                current = current.resolve(component);
                acquireDirectory(current, expected, paths, leases, observations);
            }
            DirectoryChain chain = new DirectoryChain(root, expected, paths, leases, observations);
            chain.revalidate();
            return chain;
        } catch (IOException | RuntimeException failure) {
            try { closeReverse(leases); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void acquireDirectory(Path path, WindowsVolumeProbe.Result expected, List<Path> paths,
            List<WindowsExfatNativeAccess.Lease> leases, List<WindowsExfatNativeAccess.Observation> observations)
            throws IOException {
        WindowsExfatNativeAccess.Lease lease = nativeAccess.openDirectory(path);
        leases.add(lease); // Own it before any validation that can fail.
        var observation = lease.observe();
        validate(path, true, observation, expected);
        paths.add(path);
        observations.add(observation);
    }

    public WindowsExfatNativeAccess.FileLease openProtectedFile(Path file, DirectoryChain chain,
            WindowsExfatNativeAccess.Checkpoint checkpoint) throws IOException {
        support.requireAvailable();
        synchronized (chain) {
            if (chain.owner() != this || chain.closing || !chain.root.equals(file.getParent())
                    || !WindowsVolumeProbe.ordinaryDrivePath(file.toString())) throw uncertain("Invalid held parent chain");
            chain.revalidate();
            var lease = nativeAccess.openFile(file, checkpoint);
            try { validate(file, false, lease.observe(), chain.expected); }
            catch (IOException | RuntimeException failure) {
                try { lease.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            chain.fileUsers++;
            return new ProtectedFile(file, lease, chain);
        }
    }

    private WindowsVolumeProbe.Result requireVolume(Path path) throws IOException {
        var volume = volumes.probe(path);
        if (volume.profile() != FileSystemProfile.EXFAT || !volume.ordinaryExfatRoute()) {
            throw unsupported("Unsupported exFAT filesystem or volume route");
        }
        return volume;
    }

    private void validate(Path path, boolean directory, WindowsExfatNativeAccess.Observation observation,
            WindowsVolumeProbe.Result expected) throws IOException {
        var current = requireVolume(path);
        if (!expected.nativeVolume().serial().equals(current.nativeVolume().serial())
                || !expected.nativeVolume().guid().equalsIgnoreCase(current.nativeVolume().guid())
                || !expected.nativeVolume().dosDevice().equals(current.nativeVolume().dosDevice())) {
            throw uncertain("Current volume contradicts held-chain volume");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!noFollow.equals(path.toRealPath())) throw uncertain("Follow/no-follow route disagreement");
        reconcile(path, noFollow, attributes, directory, observation, current);
    }

    static void reconcile(Path requested, Path noFollow, BasicFileAttributes nio, boolean directory,
            WindowsExfatNativeAccess.Observation nativeObservation, WindowsVolumeProbe.Result volume) throws IOException {
        String finalText = nativeObservation.finalPath();
        if (finalText == null || !finalText.startsWith("\\\\?\\")) throw uncertain("Unsupported native final path");
        String decoded = finalText.substring(4);
        if (!WindowsVolumeProbe.ordinaryDrivePath(decoded) || !Path.of(decoded).equals(noFollow)
                || !requested.equals(noFollow)) throw uncertain("Native final path contradicts direct route");
        if (nativeObservation.reparsePoint() || nio.isSymbolicLink() || nio.isOther()
                || nativeObservation.directory() != directory
                || (directory ? !nio.isDirectory() : !nio.isRegularFile())
                || !nativeObservation.volumeSerial().equals(volume.nativeVolume().serial())) {
            throw uncertain("Native/NIO classification or volume disagreement");
        }
        if (!directory && (nio.size() != nativeObservation.size()
                || !nio.lastModifiedTime().toInstant().equals(nativeObservation.modifiedInstant()))) {
            throw uncertain("Native/NIO file byte-version disagreement");
        }
    }

    public final class DirectoryChain implements ExfatRetainedRoot {
        private final Path root;
        private final WindowsVolumeProbe.Result expected;
        private final List<Path> paths;
        private final List<WindowsExfatNativeAccess.Lease> leases;
        private final List<WindowsExfatNativeAccess.Observation> observations;
        private int fileUsers;
        private volatile boolean closing;
        private boolean closed;
        private IOException closeFailure;
        private Object runtimeOwner;

        private DirectoryChain(Path root, WindowsVolumeProbe.Result expected, List<Path> paths,
                List<WindowsExfatNativeAccess.Lease> leases, List<WindowsExfatNativeAccess.Observation> observations) {
            this.root = root;
            this.expected = expected;
            this.paths = List.copyOf(paths);
            this.leases = List.copyOf(leases);
            this.observations = List.copyOf(observations);
        }

        private WindowsExfatHostFileSystem owner() { return WindowsExfatHostFileSystem.this; }

        @Override public io.github.topher6835.mediacompare.location.LocationPath resolvedRoot() {
            return ExfatReceiptValues.finalRoute(observations.getLast().finalPath());
        }
        @Override public WindowsExfatVolumeEvidence volumeEvidence() {
            var volume = expected.nativeVolume();
            return new WindowsExfatVolumeEvidence("EXFAT", volume.serial(),
                    volume.guid().substring(0, 11) + volume.guid().substring(11).toLowerCase(java.util.Locale.ROOT),
                    resolvedRoot().rootFields().getFirst() + ":\\");
        }
        @Override public int directoryCount() { return leases.size(); }
        @Override public boolean available() { return !closing; }
        @Override public synchronized void claimOwnership(Object owner) {
            if (closing || owner == null || runtimeOwner != null) throw new IllegalStateException("Chain already owned or closed");
            runtimeOwner = owner;
        }

        public synchronized void revalidate() throws IOException {
            if (closing) throw uncertain("Directory chain is closing");
            for (int index = 0; index < leases.size(); index++) {
                var current = leases.get(index).observe();
                var before = observations.get(index);
                if (!current.legacyIndex().equals(before.legacyIndex())
                        || !current.finalPath().equals(before.finalPath())) throw uncertain("Held directory contradiction");
                validate(paths.get(index), true, current, expected);
            }
        }

        @Override public synchronized void close() throws IOException {
            closing = true;
            if (fileUsers != 0) throw uncertain("Directory chain is draining protected files");
            if (!closed) {
                closed = true;
                try { closeReverse(leases); } catch (IOException failure) { closeFailure = failure; }
            }
            if (closeFailure != null) throw closeFailure;
        }
    }

    private final class ProtectedFile implements WindowsExfatNativeAccess.FileLease, java.nio.channels.SeekableByteChannel {
        private final Path path;
        private final WindowsExfatNativeAccess.FileLease lease;
        private final DirectoryChain chain;
        private boolean closed;
        private IOException closeFailure;
        ProtectedFile(Path path, WindowsExfatNativeAccess.FileLease lease, DirectoryChain chain) {
            this.path = path; this.lease = lease; this.chain = chain;
        }
        @Override public WindowsExfatNativeAccess.Observation observe() throws IOException {
            synchronized (chain) {
                chain.revalidate();
                var observation = lease.observe();
                validate(path, false, observation, chain.expected);
                return observation;
            }
        }
        @Override public java.nio.channels.SeekableByteChannel channel() { return this; }
        @Override public int read(java.nio.ByteBuffer target) throws IOException { return lease.channel().read(target); }
        @Override public long position() throws IOException { return lease.channel().position(); }
        @Override public java.nio.channels.SeekableByteChannel position(long value) throws IOException {
            lease.channel().position(value);
            return this;
        }
        @Override public long size() throws IOException { return lease.channel().size(); }
        @Override public int write(java.nio.ByteBuffer bytes) { throw new java.nio.channels.NonWritableChannelException(); }
        @Override public java.nio.channels.SeekableByteChannel truncate(long size) { throw new java.nio.channels.NonWritableChannelException(); }
        @Override public boolean isOpen() { return lease.channel().isOpen(); }
        @Override public synchronized void close() throws IOException {
            if (closed) {
                if (closeFailure != null) throw closeFailure;
                return;
            }
            closed = true;
            try { lease.close(); } catch (IOException failure) { closeFailure = failure; }
            synchronized (chain) {
                chain.fileUsers--;
                if (chain.closing && chain.fileUsers == 0) {
                    try { chain.close(); }
                    catch (IOException failure) {
                        if (closeFailure == null) closeFailure = failure; else closeFailure.addSuppressed(failure);
                    }
                }
            }
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static void closeReverse(List<? extends AutoCloseable> leases) throws IOException {
        IOException failure = null;
        for (int index = leases.size() - 1; index >= 0; index--) {
            try { leases.get(index).close(); }
            catch (Exception exception) {
                IOException next = exception instanceof IOException io ? io : new IOException("Lease close failed", exception);
                if (failure == null) failure = next; else failure.addSuppressed(next);
            }
        }
        if (failure != null) throw failure;
    }

    private static WindowsFileAccessException uncertain(String text) {
        return new WindowsFileAccessException(WindowsFileAccessException.Reason.UNCERTAIN, text);
    }
    private static WindowsFileAccessException unsupported(String text) {
        return new WindowsFileAccessException(WindowsFileAccessException.Reason.UNSUPPORTED, text);
    }
}
