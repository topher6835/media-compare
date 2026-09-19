package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MacOsApfsSourceRootEvidenceCodecTests {
    private static final String ROOT_KEY =
            "lk1:01000000000000000300000007566f6c756d657300000007417263686976650000000650686f746f73";
    private static final String FULL_JSON = "{\"version\":1,\"profile\":\"macos-local-apfs-source-root\","
            + "\"profileVersion\":1,\"locationContextId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
            + "\"locationContextRevision\":3,\"sourceLocationRevision\":7,"
            + "\"rootLocationPath\":[\"lp1\",\"unix\",[],[\"Volumes\",\"Archive\",\"Photos\"]],"
            + "\"rootLocationKey\":\"" + ROOT_KEY + "\","
            + "\"volumeUuid\":\"11111111-2222-3333-4444-555555555555\","
            + "\"rootInode\":\"123456\","
            + "\"rootBirthTime\":{\"epochSecond\":1799999900,\"nano\":123456789},"
            + "\"directory\":true,\"symbolicLink\":false,\"acceptedAtMs\":1800000000000}";

    private final MacOsApfsSourceRootEvidenceCodec codec = new MacOsApfsSourceRootEvidenceCodec();

    @Test
    void writesDeterministicJsonAndRoundTrips() {
        MacOsApfsSourceRootEvidence evidence = EvidenceTestFixtures.sourceRoot();

        assertEquals(FULL_JSON, codec.encode(evidence));
        assertEquals(evidence, codec.decode(FULL_JSON));
        assertEquals(FULL_JSON, codec.encode(codec.decode("\n" + FULL_JSON + " ")));
    }

    @ParameterizedTest(name = "rejects Source-root evidence: {0}")
    @MethodSource("invalidJson")
    void rejectsInvalidEvidenceJson(String description, String json) {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    }

    static Stream<Arguments> invalidJson() {
        return Stream.of(
                Arguments.of("malformed JSON", "{"),
                Arguments.of("non-object JSON", "[]"),
                Arguments.of("wrong number type", replace("\"locationContextRevision\":3",
                        "\"locationContextRevision\":\"3\"")),
                Arguments.of("wrong boolean type", replace("\"symbolicLink\":false",
                        "\"symbolicLink\":0")),
                Arguments.of("missing field", replace("\"rootInode\":\"123456\",", "")),
                Arguments.of("duplicate field", replace("\"version\":1",
                        "\"version\":1,\"version\":1")),
                Arguments.of("trailing scalar", FULL_JSON + "42"),
                Arguments.of("unknown version", replace("\"version\":1", "\"version\":2")),
                Arguments.of("wrong profile", replace("macos-local-apfs-source-root", "other-profile")),
                Arguments.of("wrong profile version", replace("\"profileVersion\":1",
                        "\"profileVersion\":2")),
                Arguments.of("malformed context ID", replace(EvidenceTestFixtures.CONTEXT_ID, "not-a-uuid")),
                Arguments.of("noncanonical context ID", replace(EvidenceTestFixtures.CONTEXT_ID,
                        EvidenceTestFixtures.CONTEXT_ID.toUpperCase())),
                Arguments.of("malformed Volume UUID", replace(EvidenceTestFixtures.VOLUME_UUID, "not-a-uuid")),
                Arguments.of("negative context revision", replace("\"locationContextRevision\":3",
                        "\"locationContextRevision\":-1")),
                Arguments.of("negative Source revision", replace("\"sourceLocationRevision\":7",
                        "\"sourceLocationRevision\":-1")),
                Arguments.of("negative birth nano", replace("\"nano\":123456789", "\"nano\":-1")),
                Arguments.of("birth nano overflow", replace("\"nano\":123456789",
                        "\"nano\":1000000000")),
                Arguments.of("fractional birth second", replace("\"epochSecond\":1799999900",
                        "\"epochSecond\":1.5")),
                Arguments.of("negative inode", replace("\"rootInode\":\"123456\"",
                        "\"rootInode\":\"-1\"")),
                Arguments.of("plus-prefixed inode", replace("\"rootInode\":\"123456\"",
                        "\"rootInode\":\"+123456\"")),
                Arguments.of("whitespace inode", replace("\"rootInode\":\"123456\"",
                        "\"rootInode\":\"123456 \"")),
                Arguments.of("leading-zero inode", replace("\"rootInode\":\"123456\"",
                        "\"rootInode\":\"0123456\"")),
                Arguments.of("unsigned inode overflow", replace("\"rootInode\":\"123456\"",
                        "\"rootInode\":\"18446744073709551616\"")),
                Arguments.of("negative acceptance time", replace("\"acceptedAtMs\":1800000000000",
                        "\"acceptedAtMs\":-1")),
                Arguments.of("path-key mismatch", replace(ROOT_KEY,
                        LocationKeyCodec.encode(LocationPathParser.parse(
                                LocationDialect.UNIX, "/Volumes/Archive/Other")).value())),
                Arguments.of("malformed lp1", replace(
                        "[\"lp1\",\"unix\",[],[\"Volumes\",\"Archive\",\"Photos\"]]",
                        "[\"lp2\",\"unix\",[],[\"Volumes\",\"Archive\",\"Photos\"]]")),
                Arguments.of("malformed lk1", replace(ROOT_KEY, "lk1:XYZ")),
                Arguments.of("unknown top-level field", replace("\"acceptedAtMs\":1800000000000",
                        "\"acceptedAtMs\":1800000000000,\"unknown\":true")),
                Arguments.of("missing birth field", replace("\"nano\":123456789", "\"other\":1")),
                Arguments.of("unknown birth field", replace("\"nano\":123456789",
                        "\"nano\":123456789,\"other\":1")),
                Arguments.of("oversized document", "x".repeat(
                        MacOsApfsSourceRootEvidenceCodec.MAX_DOCUMENT_UTF8_BYTES + 1)));
    }

    @Test
    void constructorRetainsFullSignedEpochSecondRange() {
        assertEquals(Long.MIN_VALUE,
                new MacOsApfsSourceRootEvidence.BirthTime(Long.MIN_VALUE, 0).epochSecond());
        assertEquals(Long.MAX_VALUE,
                new MacOsApfsSourceRootEvidence.BirthTime(Long.MAX_VALUE, 999_999_999).epochSecond());
    }

    private static String replace(String target, String replacement) {
        return FULL_JSON.replace(target, replacement);
    }
}
