package io.github.topher6835.mediacompare.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.HostFileSystem;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsNative;
import io.github.topher6835.mediacompare.filesystem.WindowsVolumeProbe;

/** Operation-local observations, not retained handles or an atomic filesystem snapshot. */
record SessionDirectoryEvidence(Path path, List<Element> elements) {
    record Element(Path path, Object identity, FileSystemProfile profile, FileStore storage) { }

    static SessionDirectoryEvidence capture(Path path, HostFileSystem host) throws IOException {
        host.requireSessionStorage(path);
        if (host.isWindows()) {
            var volume = new WindowsVolumeProbe().probe(path);
            var nativeVolume = volume.nativeVolume();
            if (volume.profile() != FileSystemProfile.NTFS || nativeVolume == null
                    || (nativeVolume.driveType() != 2 && nativeVolume.driveType() != 3)
                    || !path.getRoot().toString().equalsIgnoreCase(nativeVolume.volumeRoot())) {
                throw new IOException("Session creation requires a qualified local NTFS drive");
            }
        }
        List<Element> elements = new ArrayList<>();
        // Observe the newly created child first, minimizing its creation-to-observation gap.
        for (Path current = path; current != null; current = current.getParent()) {
            elements.add(observe(current, host));
        }
        Collections.reverse(elements);
        if (host.isWindows()) {
            String volume = ((WindowsNtfsIdentity) elements.getFirst().identity()).volumeSerial();
            for (Element element : elements) {
                if (!volume.equals(((WindowsNtfsIdentity) element.identity()).volumeSerial())) {
                    throw new IOException("Session directory crosses an NTFS volume boundary");
                }
            }
        }
        return new SessionDirectoryEvidence(path, List.copyOf(elements));
    }

    void revalidate(HostFileSystem host) throws IOException {
        if (!equals(capture(path, host))) {
            throw new IOException("Session directory identity or storage changed");
        }
    }

    boolean directChildOf(SessionDirectoryEvidence parent) {
        // Full ancestor evidence must agree, including the selected parent's identity.
        return parent.path.equals(path.getParent()) && elements.size() == parent.elements.size() + 1
                && elements.subList(0, parent.elements.size()).equals(parent.elements)
                && elements.getLast().storage().equals(parent.elements.getLast().storage());
    }

    private static Element observe(Path path, HostFileSystem host) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || host.unsafeElement(path, attributes)
                || !path.equals(path.toRealPath())) {
            throw new IOException("Session directory route is unsafe or unresolved");
        }
        FileSystemProfile profile = host.profile(path);
        if (!host.supportsProfile(profile)) throw new IOException("Session directory route storage is unsupported");
        FileStore storage = Files.getFileStore(path);
        Object identity = attributes.fileKey();
        if (host.isWindows()) {
            // Java 21 Windows fileKey may be null. Check every ancestor with native NTFS IDs.
            var observation = WindowsNtfsNative.observe(path);
            if (profile != FileSystemProfile.NTFS || observation.reparsePoint() || !observation.directory()) {
                throw new IOException("Session directory NTFS identity is unavailable");
            }
            identity = observation.identity();
        }
        if (identity == null) throw new IOException("Session directory identity is unavailable");
        return new Element(path, identity, profile, storage);
    }
}
