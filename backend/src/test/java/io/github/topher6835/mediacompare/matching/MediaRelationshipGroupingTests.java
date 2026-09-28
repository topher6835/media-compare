package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MediaRelationshipGroupingTests {
    private final MediaRelationshipGrouping grouping = new MediaRelationshipGrouping();

    @Test
    void groupsTransitiveMixedTypeEdgesAndSplitsThenReconnectsWithoutChangingEdges() {
        List<MediaRelationship> edges = List.of(
                edge(1, 2, MediaRelationshipType.EXACT),
                edge(2, 3, MediaRelationshipType.CROP),
                edge(3, 4, MediaRelationshipType.RESIZED),
                edge(4, 5, MediaRelationshipType.EDITED),
                edge(5, 6, MediaRelationshipType.VIDEO_OVERLAP),
                edge(6, 7, MediaRelationshipType.VIDEO_SEGMENT_SEQUENCE));
        Set<MediaRelationshipType> enabled = EnumSet.allOf(MediaRelationshipType.class);
        List<List<Long>> connected = List.of(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertEquals(connected, grouping.group(edges, enabled));

        enabled.remove(MediaRelationshipType.CROP);
        assertEquals(List.of(List.of(1L, 2L), List.of(3L, 4L, 5L, 6L, 7L)), grouping.group(edges, enabled));
        enabled.add(MediaRelationshipType.CROP);
        assertEquals(connected, grouping.group(edges, enabled));
    }

    @Test
    void directionDoesNotChangeConnectivity() {
        for (MediaRelationshipDirection direction : MediaRelationshipDirection.values()) {
            List<MediaRelationship> edges = List.of(
                    directedEdge(3, 2, direction), directedEdge(1, 2, direction));
            assertEquals(List.of(List.of(1L, 2L, 3L)),
                    grouping.group(edges, Set.of(MediaRelationshipType.CROP)));
        }
    }

    @Test
    void repeatedEdgesDifferentArtifactsAndCyclesDoNotDuplicateMembers() {
        MediaRelationship repeated = edge(1, 2, MediaRelationshipType.EXACT);
        MediaRelationship anotherVersion = new MediaRelationship(null, 1, 2, MediaRelationshipType.EXACT,
                MediaRelationshipDirection.UNDIRECTED, null, "{}", "test.matcher", "2", 1, "hash", "{}", 1);
        assertEquals(List.of(List.of(1L, 2L, 3L)), grouping.group(List.of(repeated, repeated,
                anotherVersion, edge(2, 3, MediaRelationshipType.EXACT), edge(3, 1, MediaRelationshipType.EXACT)),
                Set.of(MediaRelationshipType.EXACT)));
    }

    @Test
    void outputIsSortedIndependentOfInputOrderAndInputsAreUnchanged() {
        List<MediaRelationship> edges = new ArrayList<>(List.of(
                edge(30, 20, MediaRelationshipType.EDITED), edge(9, 3, MediaRelationshipType.CROP),
                edge(3, 1, MediaRelationshipType.RESIZED), edge(40, 30, MediaRelationshipType.CROP)));
        List<MediaRelationship> before = List.copyOf(edges);
        Set<MediaRelationshipType> enabled = EnumSet.allOf(MediaRelationshipType.class);
        Set<MediaRelationshipType> enabledBefore = Set.copyOf(enabled);
        List<List<Long>> result = grouping.group(edges, enabled);
        assertEquals(List.of(List.of(1L, 3L, 9L), List.of(20L, 30L, 40L)), result);
        assertEquals(before, edges);
        assertEquals(enabledBefore, enabled);
        Collections.reverse(edges);
        assertEquals(result, grouping.group(edges, enabled));
        assertThrows(UnsupportedOperationException.class, () -> result.add(List.of(99L)));
        assertThrows(UnsupportedOperationException.class, () -> result.getFirst().add(99L));
    }

    @Test
    void ignoresDisabledEdgesAndOmitsNodesWithoutEnabledConnections() {
        List<MediaRelationship> edges = List.of(edge(1, 2, MediaRelationshipType.EXACT),
                edge(2, 3, MediaRelationshipType.CROP), edge(10, 11, MediaRelationshipType.EDITED));
        assertEquals(List.of(List.of(1L, 2L)), grouping.group(edges, Set.of(MediaRelationshipType.EXACT)));
        assertEquals(List.of(), grouping.group(edges, Set.of()));
        assertEquals(List.of(), grouping.group(List.of(), EnumSet.allOf(MediaRelationshipType.class)));
        assertEquals(List.of(), grouping.group(edges, Set.of(MediaRelationshipType.VIDEO_OVERLAP)));
    }

    private static MediaRelationship edge(long a, long b, MediaRelationshipType type) {
        return new MediaRelationship(null, a, b, type, MediaRelationshipDirection.UNDIRECTED,
                null, "{}", "test.matcher", "1", 1, "hash", "{}", 1);
    }

    private static MediaRelationship directedEdge(long a, long b, MediaRelationshipDirection direction) {
        return new MediaRelationship(null, a, b, MediaRelationshipType.CROP, direction,
                null, "{}", "test.matcher", "1", 1, "hash", "{}", 1);
    }
}
