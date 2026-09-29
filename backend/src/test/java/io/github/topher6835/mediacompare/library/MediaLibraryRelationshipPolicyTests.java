package io.github.topher6835.mediacompare.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import io.github.topher6835.mediacompare.matching.ExactHashRelationshipProjection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipType;

import org.junit.jupiter.api.Test;

class MediaLibraryRelationshipPolicyTests {
    @Test
    void omittedExplicitAndRepeatedExactSelectOnlyTheImplementedShaDefinition() {
        var expected = List.of(ExactHashRelationshipProjection.DEFINITION);
        assertEquals(expected, MediaLibraryRelationshipPolicy.resolve(null).definitions());
        assertEquals(expected, MediaLibraryRelationshipPolicy.resolve(List.of(MediaRelationshipType.EXACT)).definitions());
        assertEquals(expected, MediaLibraryRelationshipPolicy.resolve(
                List.of(MediaRelationshipType.EXACT, MediaRelationshipType.EXACT)).definitions());
    }

    @Test
    void everyUnavailableKnownTypeFailsEvenWhenExactIsAlsoRequested() {
        for (MediaRelationshipType type : MediaRelationshipType.values()) {
            if (type == MediaRelationshipType.EXACT) continue;
            assertThrows(IllegalArgumentException.class, () -> MediaLibraryRelationshipPolicy.resolve(List.of(type)));
            assertThrows(IllegalArgumentException.class,
                    () -> MediaLibraryRelationshipPolicy.resolve(List.of(MediaRelationshipType.EXACT, type)));
        }
    }

    @Test
    void explicitEmptyOrNullTypeIsAnInputError() {
        assertThrows(IllegalArgumentException.class, () -> MediaLibraryRelationshipPolicy.resolve(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> MediaLibraryRelationshipPolicy.resolve(Arrays.asList(MediaRelationshipType.EXACT, null)));
    }
}
