package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MediaRelationshipProjectionRoutingTests {
    private final RecordingRepository repository = new RecordingRepository();
    private final RecordingProjection projection = new RecordingProjection();
    private final MediaRelationshipGroupService service = new MediaRelationshipGroupService(repository, projection);

    @Test
    void emptySelectionDoesNotReadEitherSource() {
        assertEquals(List.of(), service.group(new MediaRelationshipSelection(List.of())));
        assertEquals(List.of(), repository.selections);
        assertEquals(0, projection.reads);
    }

    @Test
    void exactOnlyDoesNotReadDurableRelationships() {
        assertEquals(List.of(List.of(1L, 2L)), service.group(
                new MediaRelationshipSelection(List.of(ExactHashRelationshipProjection.DEFINITION))));
        assertEquals(1, projection.reads);
        assertEquals(List.of(), repository.selections);
    }

    @Test
    void mixedSelectionQueriesOnlyTheRemainingPersistentDefinitions() {
        MediaRelationshipDefinition crop = definition(MediaRelationshipType.CROP);
        MediaRelationshipSelection persistent = new MediaRelationshipSelection(List.of(crop));
        repository.rows = List.of(edge(2, 3, crop));
        assertEquals(List.of(List.of(1L, 2L, 3L)), service.group(new MediaRelationshipSelection(
                List.of(crop, ExactHashRelationshipProjection.DEFINITION))));
        assertEquals(List.of(persistent), repository.selections);
        assertEquals(1, projection.reads);
    }

    @Test
    void cropAndOtherExactDefinitionsNeverSubstituteTheShaProjection() {
        MediaRelationshipDefinition sha = ExactHashRelationshipProjection.DEFINITION;
        List<MediaRelationshipDefinition> otherDefinitions = List.of(
                definition(MediaRelationshipType.CROP), definition(MediaRelationshipType.EXACT),
                new MediaRelationshipDefinition(sha.relationshipType(), sha.matcherId(), "2", 1,
                        sha.configurationHash(), sha.configurationJson()),
                new MediaRelationshipDefinition(sha.relationshipType(), sha.matcherId(), "1", 2,
                        sha.configurationHash(), sha.configurationJson()),
                new MediaRelationshipDefinition(sha.relationshipType(), sha.matcherId(), "1", 1,
                        "other-hash", sha.configurationJson()),
                new MediaRelationshipDefinition(sha.relationshipType(), sha.matcherId(), "1", 1,
                        sha.configurationHash(), " {} "));
        for (MediaRelationshipDefinition definition : otherDefinitions) {
            MediaRelationshipSelection selection = new MediaRelationshipSelection(List.of(definition));
            repository.rows = List.of(edge(10, 11, definition));
            assertEquals(List.of(List.of(10L, 11L)), service.group(selection));
        }
        assertEquals(otherDefinitions.stream().map(definition -> new MediaRelationshipSelection(List.of(definition)))
                .toList(), repository.selections);
        assertEquals(0, projection.reads);
    }

    private static MediaRelationshipDefinition definition(MediaRelationshipType type) {
        return new MediaRelationshipDefinition(type, "test.matcher", "1", 1, "hash", "{}");
    }

    private static MediaRelationship edge(long a, long b, MediaRelationshipDefinition definition) {
        return new MediaRelationship(null, a, b, definition.relationshipType(),
                MediaRelationshipDirection.A_TO_B, null, "{}", definition.matcherId(), definition.matcherVersion(),
                definition.configurationVersion(), definition.configurationHash(), definition.configurationJson(), 1);
    }

    private static final class RecordingRepository extends MediaRelationshipRepository {
        private final List<MediaRelationshipSelection> selections = new ArrayList<>();
        private List<MediaRelationship> rows = List.of();

        RecordingRepository() {
            super(null);
        }

        @Override
        public List<MediaRelationship> findBySelection(MediaRelationshipSelection selection) {
            selections.add(selection);
            return rows;
        }
    }

    private static final class RecordingProjection extends ExactHashRelationshipProjection {
        private int reads;

        RecordingProjection() {
            super(null);
        }

        @Override
        public List<ExactHashRelationshipEdge> project() {
            reads++;
            return List.of(new ExactHashRelationshipEdge(1, 2));
        }
    }
}
