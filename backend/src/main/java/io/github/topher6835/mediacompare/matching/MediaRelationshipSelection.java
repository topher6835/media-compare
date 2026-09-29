package io.github.topher6835.mediacompare.matching;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit compatibility policy for one operation: at most one exact definition per type. */
public record MediaRelationshipSelection(List<MediaRelationshipDefinition> definitions) {

    /** Definitions and enabled types use relationship-type declaration order. */
    public MediaRelationshipSelection {
        Objects.requireNonNull(definitions, "definitions");
        EnumSet<MediaRelationshipType> types = EnumSet.noneOf(MediaRelationshipType.class);
        for (MediaRelationshipDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (!types.add(definition.relationshipType())) {
                throw new IllegalArgumentException("Only one definition may be selected for "
                        + definition.relationshipType());
            }
        }
        definitions = definitions.stream()
                .sorted(Comparator.comparing(MediaRelationshipDefinition::relationshipType)).toList();
    }

    public MediaRelationshipSelection(Collection<MediaRelationshipDefinition> definitions) {
        this(List.copyOf(definitions));
    }

    public Set<MediaRelationshipType> enabledTypes() {
        EnumSet<MediaRelationshipType> types = EnumSet.noneOf(MediaRelationshipType.class);
        for (MediaRelationshipDefinition definition : definitions) {
            types.add(definition.relationshipType());
        }
        return Collections.unmodifiableSet(types);
    }
}
