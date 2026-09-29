package io.github.topher6835.mediacompare.matching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure connected components over supplied enabled edges; direction and matcher provenance are metadata.
 * Production callers must select compatible definitions first through {@link MediaRelationshipGroupService}.
 */
public final class MediaRelationshipGrouping {

    /** Members are ascending IDs; components are ordered by their smallest member. */
    public List<List<Long>> group(
            Collection<? extends MediaRelationshipEdge> relationships, Set<MediaRelationshipType> enabledTypes) {
        Objects.requireNonNull(relationships, "relationships");
        Objects.requireNonNull(enabledTypes, "enabledTypes");
        Map<Long, Set<Long>> neighbors = new HashMap<>();
        for (MediaRelationshipEdge relationship : relationships) {
            if (!enabledTypes.contains(relationship.relationshipType())) {
                continue;
            }
            long a = relationship.contentRecordAId();
            long b = relationship.contentRecordBId();
            neighbors.computeIfAbsent(a, ignored -> new HashSet<>()).add(b);
            neighbors.computeIfAbsent(b, ignored -> new HashSet<>()).add(a);
        }

        List<List<Long>> groups = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        for (long start : neighbors.keySet().stream().sorted().toList()) {
            if (!visited.add(start)) {
                continue;
            }
            List<Long> members = new ArrayList<>();
            ArrayDeque<Long> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                long current = pending.removeFirst();
                members.add(current);
                for (long neighbor : neighbors.get(current)) {
                    if (visited.add(neighbor)) {
                        pending.addLast(neighbor);
                    }
                }
            }
            groups.add(members.stream().sorted().toList());
        }
        return List.copyOf(groups);
    }
}
