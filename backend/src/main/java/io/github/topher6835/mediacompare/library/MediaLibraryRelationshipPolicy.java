package io.github.topher6835.mediacompare.library;

import java.util.Collection;
import java.util.List;

import io.github.topher6835.mediacompare.matching.ExactHashRelationshipProjection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipSelection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipType;

/** Public library policy: only deliberately implemented producers are available. */
public final class MediaLibraryRelationshipPolicy {
    private MediaLibraryRelationshipPolicy() {
    }

    public static MediaRelationshipSelection resolve(Collection<MediaRelationshipType> requestedTypes) {
        if (requestedTypes != null) {
            if (requestedTypes.isEmpty()) {
                throw new IllegalArgumentException("At least one library relationship type is required");
            }
            for (MediaRelationshipType type : requestedTypes) {
                if (type != MediaRelationshipType.EXACT) {
                    throw new IllegalArgumentException("Unavailable library relationship type: " + type);
                }
            }
        }
        // Default and repeated EXACT requests all select the same concrete projection definition.
        return new MediaRelationshipSelection(List.of(ExactHashRelationshipProjection.DEFINITION));
    }
}
