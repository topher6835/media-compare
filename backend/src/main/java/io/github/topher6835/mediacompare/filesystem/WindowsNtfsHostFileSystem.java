package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Local NTFS drive paths only; missing NIO file keys leave original-file identity unverifiable. */
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
        // A junction may not be reported as a symbolic link by every Windows provider.
        // A visible redirection is unsafe even when both endpoints are directories.
        return !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS));
    }

    @Override
    public HostFileCheck inspect(LocationPath location, long size, Long epochSecond, Integer nano)
            throws IOException {
        if (location.dialect() != LocationDialect.WINDOWS_DRIVE) {
            return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
        }
        Path root = path(location).getRoot();
        if (root == null || !"NTFS".equalsIgnoreCase(Files.getFileStore(root).type())) {
            return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
        }
        HostFileCheck check = HostFileSystem.super.inspect(location, size, epochSecond, nano);
        if (check.status() == HostFileStatus.ESTABLISHED
                && !Files.getFileStore(root).equals(Files.getFileStore(check.path()))) {
            return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
        }
        return check;
    }
}
