package io.github.topher6835.mediacompare.filesystem;

import io.github.topher6835.mediacompare.contentread.ExfatContentReadCapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Narrow host boundary for original-file paths and operation-local identity checks. */
public interface HostFileSystem {
    interface ProtectedOriginal extends AutoCloseable {
        java.nio.channels.SeekableByteChannel channel();
        void revalidate() throws IOException;
        @Override void close() throws IOException;
    }

    default ProtectedOriginal openExfatOriginal(
            ExfatContentReadCapture capture,
            WindowsExfatNativeAccess.Checkpoint checkpoint) throws IOException {
        throw new IOException("Protected exFAT original access unavailable on this host");
    }

    default boolean isWindows() { return false; }

    /** Host capability, without filesystem IO or decoding stored evidence. */
    default boolean supportsProfile(FileSystemProfile profile) { return false; }

    /** Fresh classification; a profile is not physical identity or permission to read. */
    default FileSystemProfile profile(Path path) throws IOException {
        return FileSystemProfile.fromType(Files.getFileStore(path).type());
    }

    default void requireSessionStorage(Path path) throws IOException {
        Path existing = path;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !supportsProfile(profile(existing))) {
            throw new IOException("Session storage is unsupported on the current host");
        }
    }

    String pathText(LocationPath location);

    default Path path(LocationPath location) {
        return Path.of(pathText(location));
    }

    /** The host may reject a link or reparse-like path even when Java calls it a directory. */
    boolean unsafeElement(Path path, BasicFileAttributes attributes) throws IOException;

    default HostFileCheck inspect(LocationPath location, long size, Long epochSecond, Integer nano)
            throws IOException {
        final Path file;
        try {
            file = path(location);
        } catch (IllegalArgumentException exception) {
            return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
        }
        if (location.components().isEmpty() || file.getRoot() == null) {
            return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
        }
        try {
            Path current = file.getRoot();
            var root = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!root.isDirectory() || unsafeElement(current, root)) {
                return HostFileCheck.failed(HostFileStatus.UNSAFE_PATH);
            }
            for (Path component : file) {
                current = current.resolve(component);
                var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (unsafeElement(current, attributes)) {
                    return HostFileCheck.failed(HostFileStatus.UNSAFE_PATH);
                }
                if (!current.equals(file)) {
                    if (!attributes.isDirectory()) return HostFileCheck.failed(HostFileStatus.UNSAFE_PATH);
                    continue;
                }
                if (!attributes.isRegularFile()) return HostFileCheck.failed(HostFileStatus.UNSAFE_PATH);
                Instant modified = attributes.lastModifiedTime().toInstant();
                if (epochSecond == null || nano == null || attributes.size() != size
                        || modified.getEpochSecond() != epochSecond || modified.getNano() != nano) {
                    return HostFileCheck.failed(HostFileStatus.STALE);
                }
                if (attributes.fileKey() == null) return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
                return HostFileCheck.established(file, attributes.fileKey());
            }
        } catch (NoSuchFileException exception) {
            return HostFileCheck.failed(HostFileStatus.MISSING);
        }
        return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
    }
}
