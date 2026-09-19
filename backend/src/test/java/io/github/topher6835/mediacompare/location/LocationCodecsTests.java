package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LocationCodecsTests {
    private final LocationPathCodec pathCodec = new LocationPathCodec();

    @Test
    void writesExactCanonicalLp1Documents() {
        assertEquals("[\"lp1\",\"unix\",[],[\"Users\",\"chris\",\"Photos\",\"a.jpg\"]]",
                pathCodec.encode(unix("/Users/chris/Photos/a.jpg")));
        assertEquals("[\"lp1\",\"win-drive\",[\"D\"],[\"Photos\",\"a.jpg\"]]",
                pathCodec.encode(drive("D:\\Photos\\a.jpg")));
        assertEquals("[\"lp1\",\"win-unc\",[\"server\",\"share\"],[\"Photos\",\"a.jpg\"]]",
                pathCodec.encode(unc("\\\\server\\share\\Photos\\a.jpg")));
    }

    @Test
    void roundTripsLp1WithoutNormalizingSpelling() {
        List<LocationPath> paths = List.of(
                unix("/Caf\u00e9"),
                unix("/Cafe\u0301"),
                drive("d:/Mixed/Case"),
                unc("//Server/Share/Mixed/Case"));

        for (LocationPath path : paths) {
            assertEquals(path, pathCodec.decode(pathCodec.encode(path)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "{}",
            "[]",
            "[\"lp1\",\"unix\",[]]",
            "[\"lp1\",\"unix\",[],[],[]]",
            "[\"lp2\",\"unix\",[],[]]",
            "[\"lp1\",\"unknown\",[],[]]",
            "[\"lp1\",1,[],[]]",
            "[\"lp1\",\"unix\",{},[]]",
            "[\"lp1\",\"unix\",[],[1]]",
            "[\"lp1\",\"unix\",[\"root\"],[]]",
            "[\"lp1\",\"win-drive\",[],[]]",
            "[\"lp1\",\"win-unc\",[\"server\"],[]]",
            "[\"lp1\",\"unix\",[],[\"..\"]]",
            "[\"lp1\",\"unix\",[],[]]{}",
            "not-json"
    })
    void rejectsMalformedOrNoncanonicalLp1(String json) {
        assertThrows(IllegalArgumentException.class, () -> pathCodec.decode(json));
    }

    @Test
    void rejectsMalformedUnicodeAndOversizedLp1Documents() {
        assertThrows(IllegalArgumentException.class,
                () -> pathCodec.decode("[\"lp1\",\"unix\",[],[\"\\uD800\"]]"));

        var components = new ArrayList<String>();
        for (int index = 0; index < 17; index++) {
            components.add("a".repeat(4_096));
        }
        LocationPath path = new LocationPath(LocationDialect.UNIX, List.of(), components);
        assertThrows(IllegalArgumentException.class, () -> pathCodec.encode(path));
        assertThrows(IllegalArgumentException.class,
                () -> pathCodec.decode(" ".repeat(LocationPathCodec.MAX_DOCUMENT_UTF8_BYTES + 1)));
    }

    @ParameterizedTest(name = "golden key {0}")
    @MethodSource("goldenKeys")
    void encodesAndDecodesFixedGoldenKeys(String description, LocationPath path, String expected) {
        LocationKey encoded = LocationKeyCodec.encode(path);
        assertEquals(expected, encoded.value());
        assertEquals(path, LocationKeyCodec.decode(expected));
        assertEquals(encoded, LocationKey.parse(expected));
    }

    static List<Arguments> goldenKeys() {
        return List.of(
                Arguments.of("Unix root", unix("/"),
                        "lk1:010000000000000000"),
                Arguments.of("one Unix component", unix("/A"),
                        "lk1:0100000000000000010000000141"),
                Arguments.of("nested Unix", unix("/Users/chris"),
                        "lk1:010000000000000002000000055573657273000000056368726973"),
                Arguments.of("Windows drive root", drive("D:\\"),
                        "lk1:0200000001000000014400000000"),
                Arguments.of("Windows drive path", drive("D:\\Photos"),
                        "lk1:02000000010000000144000000010000000650686f746f73"),
                Arguments.of("UNC path", unc("\\\\server\\share\\Photos"),
                        "lk1:030000000200000006736572766572000000057368617265000000010000000650686f746f73"),
                Arguments.of("UTF-8 byte length", unix("/Caf\u00e9"),
                        "lk1:01000000000000000100000005436166c3a9"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "lk2:010000000000000000",
            "010000000000000000",
            "lk1:",
            "lk1:0",
            "lk1:GG",
            "lk1:01000000000000000A",
            "lk1:0100000000",
            "lk1:040000000000000000",
            "lk1:01000000000000000000",
            "lk1:0100000000000000010000000241",
            "lk1:010000000000000401",
            "lk1:01000000000000000100001001",
            "lk1:01000000000000000100000001ff",
            "lk1:0100000001000000014100000000"
    })
    void rejectsMalformedOrNoncanonicalLk1(String key) {
        assertThrows(IllegalArgumentException.class, () -> LocationKeyCodec.decode(key));
        assertThrows(IllegalArgumentException.class, () -> LocationKey.parse(key));
    }

    @Test
    void enforcesLk1PayloadAndFieldBoundsDuringEncodingAndDecoding() {
        assertThrows(IllegalArgumentException.class,
                () -> LocationKeyCodec.decode("lk1:" + "00".repeat(LocationKeyCodec.MAX_PAYLOAD_BYTES + 1)));

        var components = new ArrayList<String>();
        for (int index = 0; index < 5; index++) {
            components.add(Character.toString((char) ('a' + index)).repeat(4_096));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LocationKeyCodec.encode(new LocationPath(LocationDialect.UNIX, List.of(), components)));
        assertThrows(IllegalArgumentException.class,
                () -> new LocationPath(LocationDialect.UNIX, List.of(), List.of("a".repeat(4_097))));
    }

    @Test
    void keyMatchingUsesCanonicalEncodedIdentity() {
        LocationPath exact = unix("/Volumes/A");
        LocationPath otherCase = unix("/Volumes/a");
        LocationKey exactKey = LocationKeyCodec.encode(exact);

        assertTrue(LocationKeyCodec.matches(exact, exactKey));
        assertFalse(LocationKeyCodec.matches(otherCase, exactKey));
        assertEquals(exactKey, LocationKey.parse(exactKey.value()));
        assertEquals(exactKey.hashCode(), LocationKey.parse(exactKey.value()).hashCode());
    }

    private static LocationPath unix(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }

    private static LocationPath drive(String value) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, value);
    }

    private static LocationPath unc(String value) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_UNC, value);
    }
}
