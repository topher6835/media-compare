package io.github.topher6835.mediacompare.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatHostFileSystem;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatNativeAccess;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Uses the qualified protected opens; production policy in this boundary is still disabled. */
final class WindowsExfatNativeDiscoveryAccess implements WindowsExfatDiscoveryAccess {
    private final WindowsExfatHostFileSystem host = new WindowsExfatHostFileSystem();

    @Override public Directory retainDirectory(LocationPath route) throws IOException {
        Path path = Path.of(host.pathText(route));
        var chain = host.openDirectoryChain(path);
        try {
            if (!chain.resolvedRoot().equals(route)) throw new IOException("Exact enumerated ancestry changed");
            return new HeldDirectory(path, chain);
        } catch (IOException | RuntimeException failure) {
            try { chain.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private final class HeldDirectory implements Directory {
        private final Path path;
        private final WindowsExfatHostFileSystem.DirectoryChain chain;
        HeldDirectory(Path path, WindowsExfatHostFileSystem.DirectoryChain chain) { this.path = path; this.chain = chain; }
        @Override public List<Entry> enumerate() throws IOException {
            revalidate();
            var result = new ArrayList<Entry>();
            try (var children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    var attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()
                            || !attributes.isDirectory() && !attributes.isRegularFile()) throw new IOException("Unsupported discovery element");
                    result.add(new Entry(child.getFileName().toString(), attributes.isDirectory()));
                    if (result.size() > 100_000) throw new IOException("Directory enumeration limit");
                }
            }
            revalidate();
            return List.copyOf(result);
        }
        @Override public void revalidate() throws IOException {
            chain.revalidate();
            if (!path.toString().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS).toString())) {
                throw new IOException("Enumerated directory spelling changed");
            }
        }
        @Override public File openFile(String spelling, WindowsExfatNativeAccess.Checkpoint checkpoint) throws IOException {
            Path file = path.resolve(spelling);
            var lease = host.openProtectedFile(file, chain, checkpoint);
            return new File() {
                private WindowsExfatNativeAccess.Observation baseline;
                @Override public java.nio.channels.SeekableByteChannel channel() { return lease.channel(); }
                @Override public ExfatObservationReceipt.ClassificationEvidence evidence() throws IOException {
                    var nativeEvidence = lease.observe();
                    if (baseline != null && (baseline.attributes() != nativeEvidence.attributes()
                            || baseline.creation100ns() != nativeEvidence.creation100ns()
                            || baseline.modified100ns() != nativeEvidence.modified100ns()
                            || baseline.size() != nativeEvidence.size()
                            || !baseline.volumeSerial().equals(nativeEvidence.volumeSerial())
                            || !baseline.legacyIndex().equals(nativeEvidence.legacyIndex())
                            || !baseline.finalPath().equals(nativeEvidence.finalPath()))) {
                        throw new IOException("Held native file evidence changed during hashing");
                    }
                    baseline = nativeEvidence;
                    var nio = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!nativeEvidence.finalPath().equals("\\\\?\\" + file)
                            || !file.toString().equals(file.toRealPath(LinkOption.NOFOLLOW_LINKS).toString())) {
                        throw new IOException("Held file spelling/ancestry disagrees with enumeration");
                    }
                    var modified = nio.lastModifiedTime().toInstant();
                    var volume = chain.volumeEvidence();
                    return new ExfatObservationReceipt.ClassificationEvidence(Integer.toUnsignedLong(nativeEvidence.attributes()),
                            nativeEvidence.size(), nativeEvidence.modified100ns(), nio.isRegularFile(), nio.isDirectory(),
                            nio.isSymbolicLink(), nio.isOther(), nio.size(), modified.getEpochSecond(), modified.getNano(),
                            "exFAT", nativeEvidence.finalPath(), volume.volumeSerial(), volume.volumeGuid(), nativeEvidence.legacyIndex());
                }
                @Override public void close() throws IOException { lease.close(); }
            };
        }
        @Override public void close() throws IOException { chain.close(); }
    }
}
