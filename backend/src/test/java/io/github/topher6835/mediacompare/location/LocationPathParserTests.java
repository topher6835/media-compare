package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocationPathParserTests {

    @Test
    void parsesUnixWithoutHostFilesystemSemantics() {
        assertEquals(new LocationPath(LocationDialect.UNIX, List.of(), List.of()),
                LocationPathParser.parse(LocationDialect.UNIX, "/"));
        assertEquals(List.of("Users", "chris", "Photos"),
                LocationPathParser.parse(LocationDialect.UNIX, "/Users/chris/Photos").components());
        assertEquals(List.of("literal\\backslash"),
                LocationPathParser.parse(LocationDialect.UNIX, "/literal\\backslash").components());
    }

    @Test
    void preservesUnixCaseAndUnicodeSpelling() {
        LocationPath upper = LocationPathParser.parse(LocationDialect.UNIX, "/Photos");
        LocationPath lower = LocationPathParser.parse(LocationDialect.UNIX, "/photos");
        assertNotEquals(upper, lower);

        String composed = "Caf\u00e9";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        assertNotEquals(
                LocationPathParser.parse(LocationDialect.UNIX, "/" + composed),
                LocationPathParser.parse(LocationDialect.UNIX, "/" + decomposed));
    }

    @ParameterizedTest
    @ValueSource(strings = { "relative", "./Photos", "../Photos", "/.", "/..", "/a/./b", "/a/../b",
            "//a", "/a//b", "/a/" })
    void rejectsUnsupportedUnixSyntax(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.UNIX, input));
    }

    @Test
    void rejectsNulAndMalformedUnicode() {
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.UNIX, "/a\0b"));
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.UNIX, "/\uD800"));
    }

    @Test
    void parsesWindowsDriveRootsAndBothSeparatorsWithoutNormalizingDriveCase() {
        assertEquals(new LocationPath(LocationDialect.WINDOWS_DRIVE, List.of("D"), List.of()),
                LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "D:\\"));
        assertEquals(List.of("Photos", "2024"),
                LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "D:/Photos\\2024").components());
        assertEquals(List.of("d"),
                LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "d:/").rootFields());
    }

    @ParameterizedTest
    @ValueSource(strings = { "D:Photos", "D:", "Photos", "1:\\Photos", "D:\\\\Photos", "D:/Photos/",
            "\\\\?\\D:\\Photos", "\\\\.\\D:\\Photos" })
    void rejectsUnsupportedWindowsDriveSyntax(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, input));
    }

    @ParameterizedTest
    @ValueSource(strings = { "D:\\.", "D:\\..", "D:\\a:b", "D:\\a<b", "D:\\a>b", "D:\\a\"b",
            "D:\\a|b", "D:\\a?b", "D:\\a*b", "D:\\file.", "D:\\file ", "D:\\CON",
            "D:\\con.txt", "D:\\PRN", "D:\\AUX", "D:\\NUL", "D:\\COM1", "D:\\LPT9" })
    void rejectsRestrictedWindowsComponents(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, input));
    }

    @Test
    void parsesRestrictedUncRootsAndDescendants() {
        assertEquals(new LocationPath(
                LocationDialect.WINDOWS_UNC, List.of("server", "share"), List.of()),
                LocationPathParser.parse(LocationDialect.WINDOWS_UNC, "\\\\server\\share"));
        assertEquals(new LocationPath(
                LocationDialect.WINDOWS_UNC, List.of("Server", "Share"), List.of("Photos", "2024")),
                LocationPathParser.parse(LocationDialect.WINDOWS_UNC, "//Server/Share\\Photos/2024"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "server\\share", "\\server\\share", "\\\\server", "\\\\server\\",
            "\\\\\\server\\share", "\\\\server\\share\\", "\\\\server\\\\share",
            "\\\\?\\C:\\", "//./C:/" })
    void rejectsMalformedOrUnsupportedUncSyntax(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.WINDOWS_UNC, input));
    }

    @Test
    void containmentUsesDialectRootAndWholeComponents() {
        LocationPath volumes = unix("/Volumes");
        LocationPath a = unix("/Volumes/A");
        LocationPath photos = unix("/Volumes/A/Photos");
        LocationPath ab = unix("/Volumes/AB");

        assertTrue(volumes.contains(volumes));
        assertTrue(volumes.contains(a));
        assertTrue(a.contains(photos));
        assertFalse(a.contains(ab));
        assertFalse(photos.contains(a));
        assertFalse(a.contains(LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "A:\\Photos")));
        assertFalse(
                LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "D:\\").contains(
                        LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "E:\\Photos")));
        assertFalse(
                LocationPathParser.parse(LocationDialect.WINDOWS_UNC, "\\\\server\\share").contains(
                        LocationPathParser.parse(LocationDialect.WINDOWS_UNC, "\\\\server\\other\\Photos")));
    }

    @Test
    void appendValidatesAndDoesNotMutateTheOriginal() {
        LocationPath root = unix("/Volumes/A");
        LocationPath child = root.append("Photos");

        assertEquals(List.of("Volumes", "A"), root.components());
        assertEquals(List.of("Volumes", "A", "Photos"), child.components());
        assertThrows(IllegalArgumentException.class, () -> root.append(".."));
        assertThrows(IllegalArgumentException.class,
                () -> LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "D:\\").append("bad:name"));
    }

    @Test
    void locationPathDefensivelyCopiesLists() {
        var components = new ArrayList<>(List.of("one"));
        LocationPath path = new LocationPath(LocationDialect.UNIX, List.of(), components);
        components.add("two");

        assertEquals(List.of("one"), path.components());
        assertThrows(UnsupportedOperationException.class, () -> path.components().add("three"));
    }

    private static LocationPath unix(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }
}
