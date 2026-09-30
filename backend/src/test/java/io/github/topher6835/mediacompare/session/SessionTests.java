package io.github.topher6835.mediacompare.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
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
        createLink(root.resolve("cache"), outside);
        assertThrows(IOException.class, () -> Session.open(root));
        Files.delete(root.resolve("cache"));
        createLink(root.resolve("catalog.db"), outside.resolve("catalog.db"));
        assertThrows(IOException.class, () -> Session.open(root));
    }

    @Test
    void deletesUnusedSessionAndRecognizedCatalogCacheAndSqliteSidecars() throws Exception {
        Session session = Session.create(directory.resolve("disposable"));
        Files.writeString(session.catalogPath(), "catalog");
        for (String suffix : new String[] {"-wal", "-shm", "-journal"}) {
            Files.writeString(session.root().resolve("catalog.db" + suffix), "sidecar");
        }
        Path asset = session.previewCacheRoot().resolve("small-thumbnail/ab/asset.png");
        Files.createDirectories(asset.getParent());
        Files.writeString(asset, "preview");
        try (CatalogOwnership ignored = CatalogOwnership.acquire(session.catalogJdbcUrl())) {
            // Closing ownership leaves a stale .lock file, which is safe to remove later.
        }
        Session.delete(session.root());
        assertFalse(Files.exists(session.root()));
    }

    @Test
    void activeCatalogOwnershipBlocksDeletionWithoutChangingSession() throws Exception {
        Session session = Session.create(directory.resolve("active"));
        try (CatalogOwnership ignored = CatalogOwnership.acquire(session.catalogJdbcUrl())) {
            assertThrows(IllegalStateException.class, () -> Session.delete(session.root()));
            assertTrue(Files.exists(session.manifestPath()));
            assertTrue(Files.exists(session.root().resolve("catalog.db.lock")));
        }
        Session.delete(session.root());
        assertFalse(Files.exists(session.root()));
    }

    @Test
    void unknownRootEntriesSurviveAndPreventFolderRemoval() throws Exception {
        Session session = Session.create(directory.resolve("with-user-file"));
        Path unknown = session.root().resolve("notes.txt");
        Files.writeString(unknown, "keep");
        assertThrows(java.nio.file.DirectoryNotEmptyException.class, () -> Session.delete(session.root()));
        assertEquals("keep", Files.readString(unknown));
        assertFalse(Files.exists(session.manifestPath()));
        assertFalse(Files.exists(session.root().resolve("catalog.db.lock")));
    }

    @Test
    void rejectsLinkedCacheEntriesAndNeverTouchesTheirTargets() throws Exception {
        Session session = Session.create(directory.resolve("linked-cache"));
        Path outside = directory.resolve("external-media");
        Files.createDirectory(outside);
        Path media = outside.resolve("photo.jpg");
        Files.writeString(media, "original");
        Path cache = session.root().resolve("cache");
        Files.createDirectory(cache);
        createLink(cache.resolve("external"), outside);
        assertThrows(IOException.class, () -> Session.delete(session.root()));
        assertEquals("original", Files.readString(media));
        assertTrue(Files.isSymbolicLink(cache.resolve("external")));
        assertTrue(Files.exists(session.manifestPath()));
    }

    @Test
    void rejectsNonSessionsAndLinkedOwnedFiles() throws Exception {
        Path arbitrary = directory.resolve("arbitrary");
        Files.createDirectory(arbitrary);
        Files.writeString(arbitrary.resolve("photo.jpg"), "original");
        assertThrows(IOException.class, () -> Session.delete(arbitrary));
        assertEquals("original", Files.readString(arbitrary.resolve("photo.jpg")));

        Session session = Session.create(directory.resolve("linked-catalog"));
        Path outside = directory.resolve("outside.db");
        Files.writeString(outside, "keep");
        createLink(session.catalogPath(), outside);
        assertThrows(IOException.class, () -> Session.delete(session.root()));
        assertEquals("keep", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(session.catalogPath()));
    }

    @Test
    void lifecycleCommandsExitWithoutStartingSpring() throws Exception {
        Path root = directory.resolve("from-command");
        MediaCompareApplication.main(new String[] {"session", "create", root.toString()});
        assertEquals(Session.open(root).catalogPath(), root.toRealPath().resolve("catalog.db"));
        assertFalse(Files.exists(root.resolve("catalog.db")));
        MediaCompareApplication.main(new String[] {"session", "delete", root.toString()});
        assertFalse(Files.exists(root));
        assertThrows(IllegalArgumentException.class,
                () -> MediaCompareApplication.main(new String[] {"session", "create"}));
    }

    private static void createLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Host cannot create symbolic-link test fixtures: " + exception);
        }
    }
}
