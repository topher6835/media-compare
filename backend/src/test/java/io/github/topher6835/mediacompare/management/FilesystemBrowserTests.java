package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.session.Session;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemBrowserTests {
    @TempDir Path directory;
    private final FilesystemBrowser browser = new FilesystemBrowser();

    @Test void rootsAndNavigationPreserveSpacesUnicodeAndOnlyShowDirectories() throws Exception {
        Path child = Files.createDirectory(directory.resolve("Family café 日本 + # %"));
        Files.writeString(directory.resolve("secret.txt"), "must never appear in listings");
        assertTrue(browser.roots().stream().anyMatch(root -> root.get("path").equals(directory.getRoot().toString())));
        var listing = browser.list(directory, "");
        assertEquals(directory.getParent().toString(), listing.get("parent"));
        assertEquals(List.of(Map.of("name", child.getFileName().toString(), "path", child.toString())), listing.get("directories"));
        assertEquals(child, browser.absolutePath(child.toString()));
        assertNull(browser.list(directory.getRoot(), "").get("parent"));
    }

    @Test void relativeMalformedMissingAndInaccessiblePathsAreRejected() throws Exception {
        for (String input : new String[] {"relative", "", "../escape", "bad\0path"}) {
            assertThrows(IllegalArgumentException.class, () -> browser.absolutePath(input));
        }
        assertThrows(java.io.IOException.class, () -> browser.list(directory.resolve("missing"), ""));
        assertEquals("UNAVAILABLE", browser.validate(directory.resolve("missing")).get("kind"));
        Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path inaccessible = Files.createDirectory(directory.resolve("denied"));
        var original = Files.getPosixFilePermissions(inaccessible);
        try {
            Files.setPosixFilePermissions(inaccessible, PosixFilePermissions.fromString("---------"));
            assertThrows(java.io.IOException.class, () -> browser.list(inaccessible, ""));
            assertEquals("UNAVAILABLE", browser.validate(inaccessible).get("kind"));
        } finally { Files.setPosixFilePermissions(inaccessible, original); }
    }

    @Test void boundedListingsCanBeNarrowedByName() throws Exception {
        for (int i = 0; i < 502; i++) Files.createDirectory(directory.resolve("Folder " + i));
        var listing = browser.list(directory, "");
        assertEquals(true, listing.get("truncated"));
        assertEquals(500, ((List<?>) listing.get("directories")).size());
        assertEquals(1, ((List<?>) browser.list(directory, "Folder 501").get("directories")).size());
    }

    @Test void normalFiltersMatchCaseInsensitivelyWithUnicodeSpacesAndStableSorting() throws Exception {
        Path zebra = Files.createDirectory(directory.resolve("Zebra CAFÉ 日本"));
        Path alpha = Files.createDirectory(directory.resolve("alpha café 日本"));
        Files.createDirectory(directory.resolve("unrelated"));
        assertEquals(List.of(
                Map.of("name", alpha.getFileName().toString(), "path", alpha.toString()),
                Map.of("name", zebra.getFileName().toString(), "path", zebra.toString())),
                browser.list(directory, "CaFÉ 日本").get("directories"));
        assertEquals(3, ((List<?>) browser.list(directory, "").get("directories")).size());
    }

    @Test void filtersAcceptTheLengthBoundaryAndRejectOversizedInput() throws Exception {
        assertEquals(List.of(), browser.list(directory, "a".repeat(256)).get("directories"));
        var failure = assertThrows(FilesystemBrowser.InvalidFilterException.class,
                () -> browser.list(directory, "a".repeat(257)));
        assertEquals("Folder name filter must be at most 256 characters.", failure.getMessage());
    }

    @Test void recognitionUsesSessionValidationWithoutInitializingACatalog() throws Exception {
        assertEquals("DIRECTORY", browser.validate(directory).get("kind"));
        Session session = Session.create(directory.resolve("valid"));
        assertEquals("VALID_SESSION", browser.validate(session.root()).get("kind"));
        assertFalse(Files.exists(session.catalogPath()));
        assertFalse(Files.exists(session.root().resolve("catalog.db.lock")));
        Files.writeString(session.manifestPath(), "{\"type\":\"media-compare-session\",\"formatVersion\":999}");
        assertEquals("INVALID_SESSION", browser.validate(session.root()).get("kind"));
        Files.writeString(session.manifestPath(), " ".repeat(65_537));
        assertEquals("INVALID_SESSION", browser.validate(session.root()).get("kind"));
    }

    @Test void structurallyValidButReadOnlySessionIsDistinguished() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Session session = Session.create(directory.resolve("readonly"));
        var original = Files.getPosixFilePermissions(session.root());
        try {
            Files.setPosixFilePermissions(session.root(), PosixFilePermissions.fromString("r-x------"));
            assertEquals("CANNOT_OPEN", browser.validate(session.root()).get("kind"));
        } finally { Files.setPosixFilePermissions(session.root(), original); }
    }

    @Test void linkedSessionRootsAndManifestsCannotValidate() throws Exception {
        Session session = Session.create(directory.resolve("valid"));
        Path alias = directory.resolve("alias");
        try { Files.createSymbolicLink(alias, session.root()); }
        catch (java.io.IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("Host cannot create test symlinks");
        }
        assertEquals("INVALID_SESSION", browser.validate(alias).get("kind"));
        assertEquals(session.root().toString(), browser.list(session.root(), "").get("path"));
        Path manifest = Files.move(session.manifestPath(), directory.resolve("manifest.json"));
        Files.createSymbolicLink(session.manifestPath(), manifest);
        assertEquals("INVALID_SESSION", browser.validate(session.root()).get("kind"));
    }
}
