package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostFileSystemTests {
    @TempDir Path directory;

    @Test
    void selectsOnlyExplicitSupportedHosts() {
        assertInstanceOf(MacOsHostFileSystem.class, HostFileSystems.select("Mac OS X"));
        assertInstanceOf(WindowsHostFileSystem.class, HostFileSystems.select("Windows 11"));
        assertThrows(UnsupportedOperationException.class, () -> HostFileSystems.select("Linux"));
        assertThrows(UnsupportedOperationException.class, () -> HostFileSystems.select(null));
    }

    @Test
    void windowsDriveRenderingIsPureAndUncRemainsUnsupported() {
        var windows = new WindowsHostFileSystem();
        assertEquals("C:\\", windows.pathText(LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "c:\\")));
        assertEquals("D:\\Photos\\A.jpg", windows.pathText(
                LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "d:/Photos/A.jpg")));
        assertThrows(IllegalArgumentException.class, () -> windows.pathText(
                LocationPathParser.parse(LocationDialect.WINDOWS_UNC, "\\\\server\\share\\A.jpg")));
        assertThrows(IllegalArgumentException.class, () -> windows.pathText(
                LocationPathParser.parse(LocationDialect.UNIX, "/Pictures/A.jpg")));
    }

    @Test
    void liveMacFileDistinguishesEstablishedMissingStaleUnsafeAndReplacement() throws Exception {
        Assumptions.assumeTrue(HostFileSystems.isMacOs());
        var host = HostFileSystems.current();
        Path file = Files.writeString(directory.resolve("image.jpg"), "first");
        FileTime time = FileTime.from(Instant.parse("2024-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, time);
        var location = LocationPathParser.parse(LocationDialect.UNIX, file.toRealPath().toString());
        var before = host.inspect(location, 5, time.toInstant().getEpochSecond(), time.toInstant().getNano());
        assertEquals(HostFileStatus.ESTABLISHED, before.status());
        assertEquals(file.toRealPath(), before.path());
        assertTrue(before.sameFileAs(host.inspect(location, 5,
                time.toInstant().getEpochSecond(), time.toInstant().getNano())));
        assertEquals(HostFileStatus.STALE, host.inspect(location, 4,
                time.toInstant().getEpochSecond(), time.toInstant().getNano()).status());

        Path replacement = Files.writeString(directory.resolve("replacement.jpg"), "other");
        Files.setLastModifiedTime(replacement, time);
        Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var after = host.inspect(location, 5, time.toInstant().getEpochSecond(), time.toInstant().getNano());
        assertEquals(HostFileStatus.ESTABLISHED, after.status());
        assertFalse(before.sameFileAs(after));
        Files.delete(file);
        assertEquals(HostFileStatus.MISSING, host.inspect(location, 5,
                time.toInstant().getEpochSecond(), time.toInstant().getNano()).status());

        try {
            Files.createSymbolicLink(file, directory.resolve("target.jpg"));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Host cannot create a symbolic link");
        }
        assertEquals(HostFileStatus.UNSAFE_PATH, host.inspect(location, 5,
                time.toInstant().getEpochSecond(), time.toInstant().getNano()).status());
    }

    @Test
    void realWindowsNtfsFileCanBeCheckedWhenRunOnWindows() throws Exception {
        Assumptions.assumeTrue(HostFileSystems.isWindows());
        Path file = Files.writeString(directory.resolve("image.jpg"), "bytes").toRealPath();
        Assumptions.assumeTrue("NTFS".equalsIgnoreCase(Files.getFileStore(file).type()));
        var modified = Files.getLastModifiedTime(file).toInstant();
        var location = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, file.toString());
        var check = HostFileSystems.current().inspect(location, 5,
                modified.getEpochSecond(), modified.getNano());
        var differentCase = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE,
                file.resolveSibling("IMAGE.JPG").toString());
        assertEquals(HostFileStatus.ESTABLISHED, check.status());
        assertTrue(check.sameFileAs(HostFileSystems.current().inspect(differentCase, 5,
                modified.getEpochSecond(), modified.getNano())));
        Path replacement = Files.writeString(directory.resolve("replacement.jpg"), "other");
        Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(file));
        Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var after = HostFileSystems.current().inspect(location, 5,
                modified.getEpochSecond(), modified.getNano());
        assertEquals(HostFileStatus.ESTABLISHED, after.status());
        assertFalse(check.sameFileAs(after));

        Files.delete(file);
        try {
            Files.createSymbolicLink(file, directory.resolve("target.jpg"));
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Windows host cannot create a symbolic link");
        }
        assertEquals(HostFileStatus.UNSAFE_PATH, HostFileSystems.current().inspect(location, 5,
                modified.getEpochSecond(), modified.getNano()).status());
    }
}
