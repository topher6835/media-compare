package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class MediaRelationshipTests {

    @Test
    void canonicalizesEndpointsAndTransformsEveryDirection() {
        for (MediaRelationshipDirection direction : MediaRelationshipDirection.values()) {
            MediaRelationship ordered = relationship(10, 20, direction, null, "{}", "{}");
            MediaRelationship reversed = relationship(20, 10, direction.reversed(), null, "{}", "{}");
            assertEquals(ordered, reversed);
            assertEquals(10, reversed.contentRecordAId());
            assertEquals(20, reversed.contentRecordBId());
            assertEquals(direction, reversed.direction());
        }
        assertEquals(MediaRelationshipDirection.B_TO_A,
                relationship(20, 10, MediaRelationshipDirection.A_TO_B, null, "{}", "{}").direction());
    }

    @Test
    void rejectsSelfRelationshipsAndInvalidContentIdentities() {
        for (long[] pair : List.of(new long[] {10, 10}, new long[] {0, 10}, new long[] {10, -1})) {
            assertThrows(IllegalArgumentException.class, () -> relationship(pair[0], pair[1],
                    MediaRelationshipDirection.UNDIRECTED, null, "{}", "{}"));
        }
    }

    @Test
    void acceptsNullableNormalizedConfidenceAndRejectsNonfiniteOrOutOfRangeScores() {
        assertNull(relationship(10, 20, MediaRelationshipDirection.UNDIRECTED, null, "{}", "{}").confidence());
        for (double confidence : List.of(0.0, 0.5, 1.0)) {
            assertEquals(confidence, relationship(10, 20, MediaRelationshipDirection.UNDIRECTED,
                    confidence, "{}", "{}").confidence());
        }
        for (double confidence : List.of(-0.01, 1.01, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> relationship(10, 20,
                    MediaRelationshipDirection.UNDIRECTED, confidence, "{}", "{}"));
        }
    }

    @Test
    void validatesEvidenceAndEffectiveConfigurationAsStrictJsonObjects() {
        for (String invalid : Arrays.asList(null, "", "  ", "not-json", "[]", "null", "123",
                "{", "{} {}", "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"x\":\"\uD800\"}")) {
            assertThrows(IllegalArgumentException.class, () -> relationship(10, 20,
                    MediaRelationshipDirection.UNDIRECTED, null, invalid, "{}"));
            assertThrows(IllegalArgumentException.class, () -> relationship(10, 20,
                    MediaRelationshipDirection.UNDIRECTED, null, "{}", invalid));
        }
    }

    @Test
    void boundsJsonByUtf8BytesAndPreservesSuppliedText() {
        String atLimit = "{\"x\":\"" + "é".repeat((MediaRelationship.MAX_JSON_UTF8_BYTES - 8) / 2) + "\"}";
        assertEquals(atLimit, relationship(10, 20, MediaRelationshipDirection.UNDIRECTED,
                null, atLimit, atLimit).evidenceJson());
        String overLimit = atLimit + " ";
        assertThrows(IllegalArgumentException.class, () -> relationship(10, 20,
                MediaRelationshipDirection.UNDIRECTED, null, overLimit, "{}"));
        assertThrows(IllegalArgumentException.class, () -> relationship(10, 20,
                MediaRelationshipDirection.UNDIRECTED, null, "{}", overLimit));
    }

    @Test
    void requiresCompleteProvenance() {
        for (String missing : Arrays.asList(null, "", " ")) {
            assertThrows(IllegalArgumentException.class, () -> provenance(missing, "1", 1, "hash"));
            assertThrows(IllegalArgumentException.class, () -> provenance("matcher", missing, 1, "hash"));
            assertThrows(IllegalArgumentException.class, () -> provenance("matcher", "1", 1, missing));
        }
        assertThrows(IllegalArgumentException.class, () -> provenance("matcher", "1", 0, "hash"));
        assertThrows(IllegalArgumentException.class, () -> provenance("matcher", "1", -1, "hash"));
    }

    private static MediaRelationship provenance(String matcher, String version, long configVersion, String hash) {
        return new MediaRelationship(null, 10, 20, MediaRelationshipType.EXACT,
                MediaRelationshipDirection.UNDIRECTED, null, "{}", matcher, version,
                configVersion, hash, "{}", 123);
    }

    private static MediaRelationship relationship(long a, long b, MediaRelationshipDirection direction,
            Double confidence, String evidence, String configuration) {
        return new MediaRelationship(null, a, b, MediaRelationshipType.CROP, direction,
                confidence, evidence, "test.matcher", "1", 1, "hash", configuration, 123);
    }
}
