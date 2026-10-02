package io.github.topher6835.mediacompare.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import io.github.topher6835.mediacompare.filesystem.HostFileSystem;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.location.LocationPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionFilesystemPolicyTests {
    @TempDir Path directory;

    @Test
    void rejectsUnsupportedStorageBeforeCreatingDirectoryOrWritingManifest() throws Exception {
        HostFileSystem denied = new HostFileSystem() {
            @Override public String pathText(LocationPath location) { throw new UnsupportedOperationException(); }
            @Override public boolean unsafeElement(Path path, BasicFileAttributes attributes) { return false; }
            @Override public void requireSessionStorage(Path path) throws IOException {
                throw new IOException("exFAT Session storage is unsupported");
            }
        };
        try (var hosts = mockStatic(HostFileSystems.class)) {
            hosts.when(HostFileSystems::current).thenReturn(denied);
            Path missing = directory.resolve("missing/child");
            assertThrows(IOException.class, () -> Session.create(missing));
            assertFalse(Files.exists(directory.resolve("missing")));
            Path existing = Files.createDirectory(directory.resolve("existing"));
            assertThrows(IOException.class, () -> Session.create(existing));
            assertFalse(Files.exists(existing.resolve("session.json")));
            assertThrows(IOException.class, () -> Session.open(existing));
        }
    }

    @Test
    void apfsSessionPolicySupportsMissingDescendantsButRejectsExfatCreateAndOpen() throws Exception {
        FileSystemProfile[] profile = { FileSystemProfile.APFS };
        HostFileSystem mac = new HostFileSystem() {
            @Override public String pathText(LocationPath location) { throw new UnsupportedOperationException(); }
            @Override public boolean unsafeElement(Path path, BasicFileAttributes attributes) { return false; }
            @Override public boolean supportsProfile(FileSystemProfile candidate) { return candidate == FileSystemProfile.APFS; }
            @Override public FileSystemProfile profile(Path existing) {
                assertTrue(Files.exists(existing), "Validate nearest existing ancestor before creation");
                return profile[0];
            }
        };
        try (var hosts = mockStatic(HostFileSystems.class)) {
            hosts.when(HostFileSystems::current).thenReturn(mac);
            Path root = directory.resolve("supported/child");
            Session.create(root);
            String manifest = Files.readString(root.resolve("session.json"));
            profile[0] = FileSystemProfile.EXFAT;
            assertThrows(IOException.class, () -> Session.open(root));
            assertEquals(manifest, Files.readString(root.resolve("session.json")));
            Path denied = directory.resolve("unsupported/child");
            assertThrows(IOException.class, () -> Session.create(denied));
            assertFalse(Files.exists(directory.resolve("unsupported")));
        }
    }
}
