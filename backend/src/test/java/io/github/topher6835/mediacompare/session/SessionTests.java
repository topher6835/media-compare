package io.github.topher6835.mediacompare.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTests {
    @TempDir Path directory;

    @Test
    void createsMinimalManifestAndReopensWithoutCreatingCatalogOrCache() throws Exception {
        Path root = directory.resolve("a session");
        Session created = Session.create(root);
        assertEquals(root.toRealPath(), created.root());
        assertEquals(created.root().resolve("session.json"), created.manifestPath());
        assertEquals(created.root().resolve("catalog.db"), created.catalogPath());
        assertEquals(created.root().resolve("cache/previews"), created.previewCacheRoot());
        assertEquals("""
                {
                  "type": "media-compare-session",
                  "formatVersion": 1
                }
                """, Files.readString(created.manifestPath()));
        assertFalse(Files.exists(created.catalogPath()));
        assertFalse(Files.exists(created.previewCacheRoot()));
        assertEquals(created.catalogPath(), Session.open(root.resolve("../a session")).catalogPath());
        assertThrows(IOException.class, () -> Session.create(root));
    }

    @Test
    void refusesArbitraryNonemptyDirectoryAndInvalidRoots() throws Exception {
        Path root = directory.resolve("existing");
        Files.createDirectory(root);
        Files.writeString(root.resolve("other.txt"), "keep");
        assertThrows(IOException.class, () -> Session.create(root));
        assertEquals("keep", Files.readString(root.resolve("other.txt")));
        assertThrows(IOException.class, () -> Session.open(directory.resolve("missing")));
        assertThrows(IOException.class, () -> Session.open(root));
    }

    @Test
    void rejectsInvalidAndUnsupportedManifests() throws Exception {
        Path root = directory.resolve("session");
        Session.create(root);
        Path manifest = root.resolve("session.json");
        for (String invalid : new String[] {
                "{", "[]", "{}", "{\"type\":\"other\",\"formatVersion\":1}",
                "{\"type\":\"media-compare-session\",\"formatVersion\":2}",
                "{\"type\":\"media-compare-session\",\"formatVersion\":\"1\"}",
                "{\"type\":\"media-compare-session\",\"formatVersion\":1,\"id\":\"extra\"}",
                "{\"type\":\"media-compare-session\",\"formatVersion\":1,\"formatVersion\":1}" }) {
            Files.writeString(manifest, invalid);
            assertThrows(IOException.class, () -> Session.open(root), invalid);
            assertEquals(invalid, Files.readString(manifest));
        }
    }

    @Test
    void refusesLinkedSessionOwnedPaths() throws Exception {
        Path root = directory.resolve("session");
        Session.create(root);
        Path outside = directory.resolve("outside");
        Files.createDirectory(outside);
        Files.createSymbolicLink(root.resolve("cache"), outside);
        assertThrows(IOException.class, () -> Session.open(root));
        Files.delete(root.resolve("cache"));
        Files.createSymbolicLink(root.resolve("catalog.db"), outside.resolve("catalog.db"));
        assertThrows(IOException.class, () -> Session.open(root));
    }
}
