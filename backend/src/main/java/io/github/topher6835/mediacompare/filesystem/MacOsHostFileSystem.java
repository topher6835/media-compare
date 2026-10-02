package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Path mechanics only; APFS context/Source continuity remains with its existing authority probe. */
public final class MacOsHostFileSystem implements HostFileSystem {
    @Override
    public boolean supportsProfile(FileSystemProfile profile) { return profile == FileSystemProfile.APFS; }

    @Override
    public String pathText(LocationPath location) {
        if (location.dialect() != LocationDialect.UNIX) {
            throw new IllegalArgumentException("Not a Unix host location");
        }
        return "/" + String.join("/", location.components());
    }

    @Override
    public boolean unsafeElement(Path path, BasicFileAttributes attributes) throws IOException {
        return attributes.isSymbolicLink() || attributes.isOther();
    }
}
