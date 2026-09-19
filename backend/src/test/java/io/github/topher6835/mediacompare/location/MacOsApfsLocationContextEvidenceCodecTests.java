package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MacOsApfsLocationContextEvidenceCodecTests {
    private static final String ANCHOR_KEY =
            "lk1:01000000000000000200000007566f6c756d65730000000741726368697665";
    private static final String FULL_JSON = "{\"version\":1,\"profile\":\"macos-local-apfs\","
            + "\"profileVersion\":1,\"anchorLocationPath\":[\"lp1\",\"unix\",[],"
            + "[\"Volumes\",\"Archive\"]],\"anchorLocationKey\":\"" + ANCHOR_KEY + "\","
            + "\"fileSystemType\":\"apfs\",\"volumeUuid\":\"11111111-2222-3333-4444-555555555555\","
            + "\"anchorInode\":\"2\",\"directory\":true,\"symbolicLink\":false,"
            + "\"acceptedAtMs\":1800000000000,\"diagnostics\":{\"unixDevice\":\"16777234\","
            + "\"fileStoreName\":\"/dev/disk9s1\","
            + "\"providerClass\":\"sun.nio.fs.MacOSXFileSystemProvider\"}}";

    private final MacOsApfsLocationContextEvidenceCodec codec =
            new MacOsApfsLocationContextEvidenceCodec();

    @Test
    void writesDeterministicJsonAndRoundTripsDiagnostics() {
        MacOsApfsLocationContextEvidence evidence = EvidenceTestFixtures.context();

        assertEquals(FULL_JSON, codec.encode(evidence));
        assertEquals(evidence, codec.decode(FULL_JSON));
        assertEquals(FULL_JSON, codec.encode(codec.decode(" \n" + FULL_JSON + "\t")));
    }

    @Test
    void supportsARequiredEmptyDiagnosticsObject() {
        MacOsApfsLocationContextEvidence evidence = EvidenceTestFixtures.context(
                EvidenceTestFixtures.ANCHOR,
                EvidenceTestFixtures.VOLUME_UUID,
                "2", true, false, 0,
                MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String encoded = codec.encode(evidence);

        assertEquals(evidence, codec.decode(encoded));
        assertEquals(true, encoded.endsWith("\"diagnostics\":{}}"));
    }

    @ParameterizedTest(name = "rejects context evidence: {0}")
    @MethodSource("invalidJson")
    void rejectsInvalidEvidenceJson(String description, String json) {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    }

    static Stream<Arguments> invalidJson() {
        return Stream.of(
                Arguments.of("malformed JSON", "{"),
                Arguments.of("non-object JSON", "[]"),
                Arguments.of("wrong version type", replace("\"version\":1", "\"version\":\"1\"")),
                Arguments.of("wrong boolean type", replace("\"directory\":true", "\"directory\":1")),
                Arguments.of("missing field", replace("\"anchorInode\":\"2\",", "")),
                Arguments.of("duplicate field", replace("\"version\":1", "\"version\":1,\"version\":1")),
                Arguments.of("trailing document", FULL_JSON + "{}"),
                Arguments.of("unknown version", replace("\"version\":1", "\"version\":2")),
                Arguments.of("wrong profile", replace("macos-local-apfs", "other-profile")),
                Arguments.of("wrong profile version", replace("\"profileVersion\":1", "\"profileVersion\":2")),
                Arguments.of("wrong filesystem", replace("\"fileSystemType\":\"apfs\"",
                        "\"fileSystemType\":\"hfs\"")),
                Arguments.of("malformed UUID", replace(EvidenceTestFixtures.VOLUME_UUID, "not-a-uuid")),
                Arguments.of("noncanonical UUID", replace(EvidenceTestFixtures.VOLUME_UUID,
                        "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")),
                Arguments.of("negative inode", replace("\"anchorInode\":\"2\"",
                        "\"anchorInode\":\"-2\"")),
                Arguments.of("plus-prefixed inode", replace("\"anchorInode\":\"2\"",
                        "\"anchorInode\":\"+2\"")),
                Arguments.of("whitespace inode", replace("\"anchorInode\":\"2\"",
                        "\"anchorInode\":\" 2\"")),
                Arguments.of("leading-zero inode", replace("\"anchorInode\":\"2\"",
                        "\"anchorInode\":\"02\"")),
                Arguments.of("zero inode", replace("\"anchorInode\":\"2\"",
                        "\"anchorInode\":\"0\"")),
                Arguments.of("unsigned overflow", replace("\"anchorInode\":\"2\"",
                        "\"anchorInode\":\"18446744073709551616\"")),
                Arguments.of("negative acceptance time", replace("\"acceptedAtMs\":1800000000000",
                        "\"acceptedAtMs\":-1")),
                Arguments.of("path-key mismatch", replace(ANCHOR_KEY,
                        LocationKeyCodec.encode(LocationPathParser.parse(
                                LocationDialect.UNIX, "/Volumes/Other")).value())),
                Arguments.of("malformed lp1", replace("[\"lp1\",\"unix\",[],[\"Volumes\",\"Archive\"]]",
                        "[\"lp2\",\"unix\",[],[\"Volumes\",\"Archive\"]]")),
                Arguments.of("malformed lk1", replace(ANCHOR_KEY, "lk1:xyz")),
                Arguments.of("unknown top-level field", replace("\"diagnostics\":{",
                        "\"unknown\":1,\"diagnostics\":{")),
                Arguments.of("unknown diagnostic", replace("\"diagnostics\":{",
                        "\"diagnostics\":{\"unknown\":1,")),
                Arguments.of("missing diagnostics", replace(",\"diagnostics\":{\"unixDevice\"",
                        ",\"removedDiagnostics\":{\"unixDevice\"")),
                Arguments.of("oversized document", " ".repeat(
                        MacOsApfsLocationContextEvidenceCodec.MAX_DOCUMENT_UTF8_BYTES + 1)));
    }

    @Test
    void constructorsRejectMalformedCanonicalValues() {
        assertThrows(IllegalArgumentException.class, () -> new MacOsApfsLocationContextEvidence(
                1, "macos-local-apfs", 1,
                EvidenceTestFixtures.ANCHOR,
                LocationKeyCodec.encode(EvidenceTestFixtures.ANCHOR),
                "apfs", EvidenceTestFixtures.VOLUME_UUID, "+2", true, false, 0,
                MacOsApfsLocationContextEvidence.Diagnostics.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new MacOsApfsLocationContextEvidence.Diagnostics(
                        "18446744073709551616", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MacOsApfsLocationContextEvidence.Diagnostics(null, "\uD800", null));
    }

    private static String replace(String target, String replacement) {
        return FULL_JSON.replace(target, replacement);
    }
}
