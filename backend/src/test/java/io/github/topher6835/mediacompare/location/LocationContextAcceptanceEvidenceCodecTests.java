package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LocationContextAcceptanceEvidenceCodecTests {
    private static final String ID = EvidenceTestFixtures.CONTEXT_ID;
    private final LocationContextAcceptanceEvidenceCodec codec = new LocationContextAcceptanceEvidenceCodec();
    private final MacOsApfsLocationContextEvidenceCodec apfsCodec =
            new MacOsApfsLocationContextEvidenceCodec();

    @Test
    void deterministicRoundTrip() {
        LocationContextAcceptanceEvidence evidence = new LocationContextAcceptanceEvidence(
                1, ID, 8, EvidenceTestFixtures.context());
        String expected = "{\"version\":1,\"contextId\":\"" + ID
                + "\",\"contextRevision\":8,\"macOsApfsEvidence\":"
                + apfsCodec.encode(EvidenceTestFixtures.context()) + "}";

        assertEquals(expected, codec.encode(evidence));
        assertEquals(evidence, codec.decode(expected));
        assertEquals(expected, codec.encode(codec.decode(" \n" + expected)));
    }

    @ParameterizedTest(name = "rejects envelope: {0}")
    @MethodSource("invalidJson")
    void rejectsInvalidEnvelope(String description, String json) {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    }

    static Stream<Arguments> invalidJson() {
        String valid = validJson();
        return Stream.of(
                Arguments.of("noncanonical UUID", valid.replace(ID, ID.toUpperCase())),
                Arguments.of("invalid UUID", valid.replace(ID, "invalid")),
                Arguments.of("negative revision", valid.replace("\"contextRevision\":8", "\"contextRevision\":-1")),
                Arguments.of("wrong revision type", valid.replace("\"contextRevision\":8", "\"contextRevision\":\"8\"")),
                Arguments.of("wrong version", valid.replace("\"version\":1", "\"version\":2")),
                Arguments.of("unknown field", valid.replace("\"version\":1", "\"version\":1,\"extra\":true")),
                Arguments.of("missing field", valid.replace("\"contextRevision\":8,", "")),
                Arguments.of("duplicate field", valid.replace("\"version\":1", "\"version\":1,\"version\":1")),
                Arguments.of("nested unknown field", valid.replace("\"profileVersion\":1", "\"profileVersion\":1,\"extra\":true")),
                Arguments.of("nested malformed APFS", valid.replace("\"fileSystemType\":\"apfs\"", "\"fileSystemType\":\"hfs\"")),
                Arguments.of("nested wrong type", valid.replace(
                        new MacOsApfsLocationContextEvidenceCodec().encode(EvidenceTestFixtures.context()), "1")),
                Arguments.of("oversized document", " ".repeat(LocationContextAcceptanceEvidenceCodec.MAX_DOCUMENT_UTF8_BYTES + 1)));
    }

    @Test
    void constructorsRejectInvalidProvenance() {
        assertThrows(IllegalArgumentException.class, () -> new LocationContextAcceptanceEvidence(
                1, ID.toUpperCase(), 8, EvidenceTestFixtures.context()));
        assertThrows(IllegalArgumentException.class, () -> new LocationContextAcceptanceEvidence(
                1, ID, -1, EvidenceTestFixtures.context()));
    }

    private static String validJson() {
        return new LocationContextAcceptanceEvidenceCodec().encode(new LocationContextAcceptanceEvidence(
                1, ID, 8, EvidenceTestFixtures.context()));
    }
}
