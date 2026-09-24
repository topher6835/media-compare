package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SourceBindingEvidenceCodecTests {
    private final SourceBindingEvidenceCodec codec = new SourceBindingEvidenceCodec();

    @Test
    void deterministicRoundTrip() {
        SourceBindingEvidence evidence = new SourceBindingEvidence(1, 42, EvidenceTestFixtures.sourceRoot());
        String expected = "{\"version\":1,\"sourceId\":42,\"macOsApfsSourceRootEvidence\":"
                + new MacOsApfsSourceRootEvidenceCodec().encode(EvidenceTestFixtures.sourceRoot()) + "}";
        assertEquals(expected, codec.encode(evidence));
        assertEquals(evidence, codec.decode(expected));
        assertEquals(expected, codec.encode(codec.decode(" \n" + expected)));
    }

    @ParameterizedTest(name = "rejects binding envelope: {0}")
    @MethodSource("invalidJson")
    void rejectsInvalidEnvelope(String description, String json) {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json));
    }

    static Stream<Arguments> invalidJson() {
        String valid = validJson();
        String nested = new MacOsApfsSourceRootEvidenceCodec().encode(EvidenceTestFixtures.sourceRoot());
        return Stream.of(
                Arguments.of("unknown field", valid.replace("\"sourceId\":42", "\"sourceId\":42,\"extra\":1")),
                Arguments.of("missing field", valid.replace("\"sourceId\":42,", "")),
                Arguments.of("duplicate field", valid.replace("\"sourceId\":42", "\"sourceId\":42,\"sourceId\":42")),
                Arguments.of("wrong ID type", valid.replace("\"sourceId\":42", "\"sourceId\":\"42\"")),
                Arguments.of("zero ID", valid.replace("\"sourceId\":42", "\"sourceId\":0")),
                Arguments.of("negative ID", valid.replace("\"sourceId\":42", "\"sourceId\":-1")),
                Arguments.of("wrong version", valid.replaceFirst("\"version\":1", "\"version\":2")),
                Arguments.of("missing nested", valid.replace(nested, "null")),
                Arguments.of("malformed nested", valid.replace("\"profileVersion\":1", "\"profileVersion\":2")),
                Arguments.of("oversized", " ".repeat(SourceBindingEvidenceCodec.MAX_DOCUMENT_UTF8_BYTES + 1)));
    }

    @Test
    void constructorsRejectInvalidId() {
        assertThrows(IllegalArgumentException.class, () -> new SourceBindingEvidence(
                1, 0, EvidenceTestFixtures.sourceRoot()));
    }

    private static String validJson() {
        return new SourceBindingEvidenceCodec().encode(new SourceBindingEvidence(
                1, 42, EvidenceTestFixtures.sourceRoot()));
    }
}
