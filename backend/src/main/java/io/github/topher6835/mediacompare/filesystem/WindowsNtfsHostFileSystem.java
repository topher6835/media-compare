package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Local NTFS drive paths only; native IDs supply operation-local physical identity. */
public final class WindowsNtfsHostFileSystem implements HostFileSystem {
    @Override
    public String pathText(LocationPath location) {
        if (location.dialect() != LocationDialect.WINDOWS_DRIVE) {
            throw new IllegalArgumentException("Only local Windows drive locations are supported");
        }
        String drive = location.rootFields().getFirst().toUpperCase(Locale.ROOT);
        return drive + ":\\" + String.join("\\", location.components());
    }

    @Override
    public boolean unsafeElement(Path path, BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther()) return true;
        if (WindowsNtfsNative.observe(path).reparsePoint()) return true;
        return !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS));
    }

    @Override
    public HostFileCheck inspect(LocationPath location, long size, Long epochSecond, Integer nano)
            throws IOException {
        if (location.dialect() != LocationDialect.WINDOWS_DRIVE) {
            return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
        }
        var before = WindowsNtfsPathInspector.inspect(location, false);
        if (before.status() != HostFileStatus.ESTABLISHED) return HostFileCheck.failed(before.status());
        Path file = path(location);
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var modified = attributes.lastModifiedTime().toInstant();
            if (epochSecond == null || nano == null || attributes.size() != size
                    || modified.getEpochSecond() != epochSecond || modified.getNano() != nano) {
                return HostFileCheck.failed(HostFileStatus.STALE);
            }
        } catch (NoSuchFileException exception) {
            return HostFileCheck.failed(HostFileStatus.MISSING);
        }
        var after = WindowsNtfsPathInspector.inspect(location, false);
        if (after.status() != HostFileStatus.ESTABLISHED) return HostFileCheck.failed(after.status());
        if (!before.identity().equals(after.identity())) return HostFileCheck.failed(HostFileStatus.STALE);
        return HostFileCheck.established(file, after.identity());
    }
}
