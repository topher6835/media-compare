package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MediaRelationshipSelectionTests {

    @Test
    void emptySelectionEnablesNoTypes() {
        MediaRelationshipSelection selection = new MediaRelationshipSelection(List.of());
        assertEquals(List.of(), selection.definitions());
        assertEquals(Set.of(), selection.enabledTypes());
    }

    @Test
    void oneDefinitionEnablesExactlyItsType() {
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP, "1");
        MediaRelationshipSelection selection = new MediaRelationshipSelection(List.of(crop));
        assertEquals(List.of(crop), selection.definitions());
        assertEquals(Set.of(MediaRelationshipType.CROP), selection.enabledTypes());
    }

    @Test
    void definitionsAndTypesHaveDeterministicDeclarationOrderRegardlessOfInputOrder() {
        List<MediaRelationshipDefinition> definitions = Arrays.stream(MediaRelationshipType.values())
                .map(type -> definition(type, "1")).toList();
        List<MediaRelationshipDefinition> reversed = new ArrayList<>(definitions);
        Collections.reverse(reversed);
        MediaRelationshipSelection selection = new MediaRelationshipSelection(new LinkedHashSet<>(reversed));
        assertEquals(definitions, selection.definitions());
        assertEquals(List.of(MediaRelationshipType.values()), new ArrayList<>(selection.enabledTypes()));
        assertEquals(new MediaRelationshipSelection(definitions), selection);
    }

    @Test
    void rejectsBothRepeatedAndDifferentDefinitionsForTheSameType() {
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP, "1");
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRelationshipSelection(List.of(crop, crop)));
        assertThrows(IllegalArgumentException.class, () -> new MediaRelationshipSelection(
                List.of(crop, definition(MediaRelationshipType.CROP, "2"))));
    }

    @Test
    void copiesCallerInputAndExposesImmutableDefinitionsAndTypes() {
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP, "1");
        MediaRelationshipDefinition resized = definition(MediaRelationshipType.RESIZED, "1");
        List<MediaRelationshipDefinition> input = new ArrayList<>(List.of(crop, resized));
        MediaRelationshipSelection selection = new MediaRelationshipSelection(input);
        input.clear();
        assertEquals(List.of(resized, crop), selection.definitions());
        assertEquals(Set.of(MediaRelationshipType.RESIZED, MediaRelationshipType.CROP), selection.enabledTypes());
        assertThrows(UnsupportedOperationException.class, () -> selection.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> selection.enabledTypes().clear());
    }

    @Test
    void rejectsNullInputAndNullDefinitions() {
        assertThrows(NullPointerException.class,
                () -> new MediaRelationshipSelection((List<MediaRelationshipDefinition>) null));
        assertThrows(NullPointerException.class, () -> new MediaRelationshipSelection(Arrays.asList(
                definition(MediaRelationshipType.CROP, "1"), null)));
    }

    private static MediaRelationshipDefinition definition(MediaRelationshipType type, String version) {
        return new MediaRelationshipDefinition(type, "test.matcher", version, 1, "hash", "{}");
    }
}
