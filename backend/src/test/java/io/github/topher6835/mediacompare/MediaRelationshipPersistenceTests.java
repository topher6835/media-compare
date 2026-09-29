package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.matching.MediaRelationship;
import io.github.topher6835.mediacompare.matching.MediaRelationshipDefinition;
import io.github.topher6835.mediacompare.matching.MediaRelationshipDirection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipRepository;
import io.github.topher6835.mediacompare.matching.MediaRelationshipSelection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:relationship-tests-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaRelationshipPersistenceTests {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CatalogRepository catalog;
    @Autowired private MediaRelationshipRepository relationships;

    @AfterEach
    void clearRows() {
        jdbcTemplate.update("DELETE FROM media_relationship");
        jdbcTemplate.update("DELETE FROM content_record");
    }

    @Test
    void roundTripsCanonicalDirectionNullableConfidenceEvidenceAndProvenance() {
        long a = content();
        long b = content();
        MediaRelationship stored = relationships.insert(new MediaRelationship(null, b, a,
                MediaRelationshipType.CROP, MediaRelationshipDirection.A_TO_B, null,
                " {\"version\":1,\"crop\":{\"x\":3,\"y\":4},\"label\":\"café\"} ",
                "test.crop", "2.1", 3, "config-hash", " {\"threshold\":0.8} ", 12345));
        assertEquals(stored, relationships.findById(stored.id()).orElseThrow());
        assertEquals(a, stored.contentRecordAId());
        assertEquals(b, stored.contentRecordBId());
        assertEquals(MediaRelationshipDirection.B_TO_A, stored.direction());
        assertNull(stored.confidence());
        assertEquals(List.of(a + "|" + b + "|B_TO_A"), jdbcTemplate.query("""
                SELECT content_record_a_id, content_record_b_id, direction FROM media_relationship
                """, (row, index) -> row.getLong(1) + "|" + row.getLong(2) + "|" + row.getString(3)));
        assertTrue(relationships.findById(stored.id() + 1).isEmpty());
    }

    @Test
    void rejectsReversedLogicalDuplicatesForDirectedAndUndirectedResults() {
        long a = content();
        long b = content();
        for (MediaRelationshipType type : List.of(MediaRelationshipType.CROP, MediaRelationshipType.EXACT)) {
            MediaRelationshipDirection direction = type == MediaRelationshipType.CROP
                    ? MediaRelationshipDirection.A_TO_B : MediaRelationshipDirection.UNDIRECTED;
            relationships.insert(relationship(a, b, type, direction, "1", 1, "hash", null));
            assertThrows(DataAccessException.class, () -> relationships.insert(
                    relationship(b, a, type, direction.reversed(), "1", 1, "hash", 0.9)));
        }
        assertEquals(2, relationships.findByTypes(EnumSet.allOf(MediaRelationshipType.class)).size());
    }

    @Test
    void retainsDistinctMatcherVersionsAndConfigurationsWithNormalizedScores() {
        long a = content();
        long b = content();
        MediaRelationship first = relationships.insert(relationship(a, b, MediaRelationshipType.RESIZED,
                MediaRelationshipDirection.UNDIRECTED, "1", 1, "hash", 0.0));
        MediaRelationship version = relationships.insert(relationship(a, b, MediaRelationshipType.RESIZED,
                MediaRelationshipDirection.UNDIRECTED, "2", 1, "hash", 0.5));
        MediaRelationship configVersion = relationships.insert(relationship(a, b, MediaRelationshipType.RESIZED,
                MediaRelationshipDirection.UNDIRECTED, "1", 2, "hash", 1.0));
        MediaRelationship configHash = relationships.insert(relationship(a, b, MediaRelationshipType.RESIZED,
                MediaRelationshipDirection.UNDIRECTED, "1", 1, "other-hash", null));
        assertEquals(List.of(first, version, configVersion, configHash),
                relationships.findByTypes(Set.of(MediaRelationshipType.RESIZED)));
    }

    @Test
    void filtersByEnabledTypesIncludingEmptySetAndDoesNotPublishHashArtifacts() {
        long a = content();
        long b = content();
        List<MediaRelationship> stored = EnumSet.allOf(MediaRelationshipType.class).stream()
                .map(type -> relationships.insert(relationship(a, b, type,
                        MediaRelationshipDirection.UNDIRECTED, "1", 1, "hash", null))).toList();
        assertEquals(stored, relationships.findByTypes(EnumSet.allOf(MediaRelationshipType.class)));
        assertEquals(stored.stream().filter(row -> row.relationshipType() == MediaRelationshipType.EXACT
                        || row.relationshipType() == MediaRelationshipType.VIDEO_OVERLAP).toList(),
                relationships.findByTypes(Set.of(MediaRelationshipType.EXACT, MediaRelationshipType.VIDEO_OVERLAP)));
        assertEquals(List.of(), relationships.findByTypes(Set.of()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_record", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM content_hash", Integer.class));
    }

    private long content() {
        return catalog.insert(new ContentRecord(null, 42, 1)).id();
    }

    @Test
    void selectionMatchesEveryDefinitionFieldAndRetainsAllHistoryInIdOrder() {
        long a = content();
        long b = content();
        long c = content();
        MediaRelationshipDefinition selected = new MediaRelationshipDefinition(
                MediaRelationshipType.CROP, "test.crop", "1", 1, "hash", "{\"x\":1}");
        MediaRelationshipDefinition resized = new MediaRelationshipDefinition(
                MediaRelationshipType.RESIZED, "test.crop", "1", 1, "hash", "{\"x\":1}");
        MediaRelationship first = relationships.insert(definedRelationship(a, b, selected));
        List<MediaRelationshipDefinition> otherDefinitions = List.of(
                new MediaRelationshipDefinition(MediaRelationshipType.CROP, "other.crop", "1", 1, "hash", "{\"x\":1}"),
                new MediaRelationshipDefinition(MediaRelationshipType.CROP, "test.crop", "99", 1, "hash", "{\"x\":1}"),
                new MediaRelationshipDefinition(MediaRelationshipType.CROP, "test.crop", "1", 99, "hash", "{\"x\":1}"),
                new MediaRelationshipDefinition(MediaRelationshipType.CROP, "test.crop", "1", 1, "other-hash", "{\"x\":1}"),
                new MediaRelationshipDefinition(MediaRelationshipType.CROP, "test.crop", "1", 1, "hash", "{\"x\":2}"),
                new MediaRelationshipDefinition(MediaRelationshipType.CROP, "test.crop", "1", 1, "hash", " {\"x\":1} "));
        for (MediaRelationshipDefinition definition : otherDefinitions) {
            // V8 uniqueness excludes JSON; each historical definition uses its own pair.
            relationships.insert(definedRelationship(b, content(), definition));
        }
        MediaRelationship mixedType = relationships.insert(definedRelationship(a, b, resized));
        MediaRelationship last = relationships.insert(definedRelationship(a, c, selected));
        List<MediaRelationship> history = relationships.findByTypes(EnumSet.allOf(MediaRelationshipType.class));

        assertEquals(List.of(first, last), relationships.findBySelection(
                new MediaRelationshipSelection(List.of(selected))));
        assertEquals(List.of(first, mixedType, last), relationships.findBySelection(
                new MediaRelationshipSelection(List.of(selected, resized))));
        assertEquals(List.of(), relationships.findBySelection(new MediaRelationshipSelection(List.of())));
        assertEquals(8, relationships.findByTypes(Set.of(MediaRelationshipType.CROP)).size());
        assertEquals(history, relationships.findByTypes(EnumSet.allOf(MediaRelationshipType.class)));
        for (MediaRelationship row : history) {
            assertEquals(row, relationships.findById(row.id()).orElseThrow());
        }
    }

    @Test
    void selectionParameterizesProvenanceContainingSqlPunctuation() {
        long a = content();
        long b = content();
        relationships.insert(relationship(a, b, MediaRelationshipType.CROP,
                MediaRelationshipDirection.UNDIRECTED, "1", 1, "hash", null));
        MediaRelationshipDefinition definition = new MediaRelationshipDefinition(MediaRelationshipType.CROP,
                "test.crop' OR 1=1 --", "version'", 1, "hash'", "{\"label\":\"it's exact\"}");
        MediaRelationship selected = relationships.insert(definedRelationship(a, b, definition));
        assertEquals(List.of(selected), relationships.findBySelection(
                new MediaRelationshipSelection(List.of(definition))));
    }

    private static MediaRelationship definedRelationship(long a, long b, MediaRelationshipDefinition definition) {
        return new MediaRelationship(null, a, b, definition.relationshipType(),
                MediaRelationshipDirection.UNDIRECTED, null, "{}", definition.matcherId(),
                definition.matcherVersion(), definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), 100);
    }

    private static MediaRelationship relationship(long a, long b, MediaRelationshipType type,
            MediaRelationshipDirection direction, String version, long configVersion, String hash, Double score) {
        return new MediaRelationship(null, a, b, type, direction, score, "{}",
                "test.matcher", version, configVersion, hash, "{}", 100);
    }
}
