package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Checks every component on one local NTFS drive before returning its native identity. */
public final class WindowsNtfsPathInspector {
    private WindowsNtfsPathInspector() { }

    public static Result inspect(LocationPath location, boolean directory) {
        if (!HostFileSystems.current().supportsProfile(FileSystemProfile.NTFS)
                || location.dialect() != LocationDialect.WINDOWS_DRIVE) {
            return Result.failed(HostFileStatus.UNVERIFIABLE);
        }
        final Path path;
        try {
            path = HostFileSystems.current().path(location);
        } catch (IllegalArgumentException exception) {
            return Result.failed(HostFileStatus.UNVERIFIABLE);
        }
        Path root = path.getRoot();
        if (root == null) return Result.failed(HostFileStatus.UNVERIFIABLE);
        try {
            if (!"NTFS".equalsIgnoreCase(Files.getFileStore(root).type())) {
                return Result.failed(HostFileStatus.UNVERIFIABLE);
            }
            var rootResult = inspectElement(root, true);
            if (rootResult.status() != HostFileStatus.ESTABLISHED) return rootResult;
            WindowsNtfsIdentity rootIdentity = rootResult.identity();
            Path current = root;
            Result last = rootResult;
            for (Path segment : path) {
                current = current.resolve(segment);
                last = inspectElement(current, !current.equals(path) || directory);
                if (last.status() != HostFileStatus.ESTABLISHED) return last;
                if (!last.identity().volumeSerial().equals(rootIdentity.volumeSerial())
                        || !"NTFS".equalsIgnoreCase(Files.getFileStore(current).type())) {
                    return Result.failed(HostFileStatus.UNSAFE_PATH);
                }
            }
            var rootAfter = inspectElement(root, true);
            if (rootAfter.status() != HostFileStatus.ESTABLISHED
                    || !rootIdentity.equals(rootAfter.identity())) {
                return Result.failed(HostFileStatus.UNVERIFIABLE);
            }
            return last;
        } catch (NoSuchFileException exception) {
            return Result.failed(HostFileStatus.MISSING);
        } catch (IOException | SecurityException exception) {
            return Result.failed(HostFileStatus.UNVERIFIABLE);
        }
    }

    private static Result inspectElement(Path path, boolean directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            return Result.failed(HostFileStatus.UNSAFE_PATH);
        }
        var nativeObservation = WindowsNtfsNative.observe(path);
        if (nativeObservation.reparsePoint()
                || nativeObservation.directory() != directory
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            return Result.failed(HostFileStatus.UNSAFE_PATH);
        }
        return Result.established(nativeObservation.identity());
    }

    public record Result(HostFileStatus status, WindowsNtfsIdentity identity) {
        public static Result established(WindowsNtfsIdentity identity) {
            return new Result(HostFileStatus.ESTABLISHED, identity);
        }

        public static Result failed(HostFileStatus status) {
            if (status == HostFileStatus.ESTABLISHED) throw new IllegalArgumentException();
            return new Result(status, null);
        }
    }
}
