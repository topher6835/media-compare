package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;

import org.junit.jupiter.api.Test;

class ExactHashRelationshipDefinitionTests {
    @Test
    void projectionHasAStableExplicitDefinitionSeparateFromHashAnalysis() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("{}".getBytes(StandardCharsets.UTF_8)));
        MediaRelationshipDefinition expected = new MediaRelationshipDefinition(
                MediaRelationshipType.EXACT, "builtin.sha256.exact-projection", "1", 1, hash, "{}");
        assertEquals(expected, ExactHashRelationshipProjection.DEFINITION);
        assertEquals(Sha256AnalysisDefinition.CONFIGURATION_HASH, expected.configurationHash());
        assertNotEquals(Sha256AnalysisDefinition.ANALYZER_ID, expected.matcherId());
        assertNotEquals(new MediaRelationshipDefinition(MediaRelationshipType.EXACT,
                "test.exact", "1", 1, hash, "{}"), expected);
    }

    @Test
    void transientEdgesValidateAndCanonicalizeContentEndpoints() {
        ExactHashRelationshipEdge edge = new ExactHashRelationshipEdge(9, 3);
        assertEquals(new ExactHashRelationshipEdge(3, 9), edge);
        assertEquals(MediaRelationshipType.EXACT, edge.relationshipType());
        for (long[] pair : List.of(new long[] {0, 1}, new long[] {1, -1}, new long[] {2, 2})) {
            assertThrows(IllegalArgumentException.class, () -> new ExactHashRelationshipEdge(pair[0], pair[1]));
        }
    }

    @Test
    void pureGroupingConsumesBothTransientAndDurableEdges() {
        MediaRelationship durable = new MediaRelationship(null, 9, 20, MediaRelationshipType.CROP,
                MediaRelationshipDirection.B_TO_A, null, "{}", "test.crop", "1", 1, "hash", "{}", 1);
        List<MediaRelationshipEdge> edges = List.of(new ExactHashRelationshipEdge(9, 3), durable);
        assertEquals(List.of(List.of(3L, 9L, 20L)), new MediaRelationshipGrouping().group(
                edges, Set.of(MediaRelationshipType.EXACT, MediaRelationshipType.CROP)));
    }
}
