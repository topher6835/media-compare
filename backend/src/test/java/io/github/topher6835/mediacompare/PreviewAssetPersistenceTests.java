package io.github.topher6835.mediacompare;

import static io.github.topher6835.mediacompare.preview.PreviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import io.github.topher6835.mediacompare.preview.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:preview-persistence-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PreviewAssetPersistenceTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PreviewAssetRepository assets;

    @BeforeEach
    void seedCatalog() {
        seed(jdbc);
    }

    @AfterEach
    void clear() {
        for (String table : List.of("preview_asset", "source_membership", "file_entry", "content_record", "source")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void insertsAndRoundTripsImmutableMetadataAndCurrentEvidence() {
        PreviewAsset stored = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10));
        assertTrue(stored.id() > 0);
        assertEquals(stored, assets.findByAssetKey(stored.assetKey()).orElseThrow());
        assertEquals(stored, assets.findCurrent(7, definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
        assertTrue(assets.findByAssetKey("a".repeat(64)).isEmpty());
        assertTrue(assets.findCurrent(999, definition(), PreviewKind.SMALL_THUMBNAIL).isEmpty());
        assertTrue(assets.findCurrent(7, definition(), PreviewKind.MEDIUM_PREVIEW).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> assets.findByAssetKey("malformed"));
        assertThrows(IllegalArgumentException.class, () -> assets.findCurrent(0, definition(), PreviewKind.SMALL_THUMBNAIL));
        assertThrows(DataAccessException.class, () -> assets.insert(stored));
    }

    @ParameterizedTest
    @ValueSource(strings = {"current_content_id = 12", "current_content_id = NULL", "observation_revision = 4",
            "size_bytes = 43", "modified_time_epoch_second = 1700000001", "modified_time_nano = 123456790",
            "modified_time_epoch_second = NULL, modified_time_nano = NULL"})
    void evidenceChangesInvalidateCurrentLookupButLeaveImmutableRows(String assignment) {
        PreviewAsset stored = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10));
        jdbc.update("UPDATE file_entry SET " + assignment + " WHERE id = 7");
        assertTrue(assets.findCurrent(7, definition(), PreviewKind.SMALL_THUMBNAIL).isEmpty());
        assertEquals(stored, assets.findByAssetKey(stored.assetKey()).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM preview_asset", Integer.class));
    }

    @Test
    void eachDefinitionChangeRequiresItsOwnAssetAndVersionsCoexist() {
        PreviewAsset first = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10));
        for (var changed : List.of(
                new PreviewDefinition("other.preview", "1", 1, "config-hash", "{}"),
                new PreviewDefinition("test.preview", "2", 1, "config-hash", "{}"),
                new PreviewDefinition("test.preview", "1", 2, "config-hash", "{}"),
                new PreviewDefinition("test.preview", "1", 1, "other-hash", "{}"))) {
            assertTrue(assets.findCurrent(7, changed, PreviewKind.SMALL_THUMBNAIL).isEmpty());
            PreviewAsset stored = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, changed, 10));
            assertEquals(stored, assets.findCurrent(7, changed, PreviewKind.SMALL_THUMBNAIL).orElseThrow());
        }
        assertEquals(first, assets.findCurrent(7, definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM preview_asset", Integer.class));
    }

    @Test
    void overlappingSourcesMembershipCountPathsAndPresenceDoNotChangeCacheIdentity() {
        PreviewAsset stored = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10));
        jdbc.update("""
                INSERT INTO source (id, name, root_path, root_path_key, created_at_ms, updated_at_ms)
                VALUES (1, 'parent', '/archive', '/archive', 1, 1), (2, 'child', '/archive/photos', '/archive/photos', 1, 1)
                """);
        for (long sourceId : List.of(1L, 2L)) {
            String path = sourceId == 1 ? "photos/photo.png" : "photo.png";
            jdbc.update("""
                    INSERT INTO source_membership (source_id, file_entry_id, relative_path, path_key,
                        applicability_status, presence_status, observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms)
                    VALUES (?, 7, ?, ?, 'ACTIVE', 'PRESENT', 3, 1, 2)
                    """, sourceId, path, path);
            assertEquals(stored, assets.findCurrent(7, definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
            assertThrows(DataAccessException.class,
                    () -> assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10)));
        }
        jdbc.update("UPDATE source_membership SET presence_status = 'MISSING', applicability_status = 'RETIRED'");
        assertEquals(stored, assets.findCurrent(7, definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM preview_asset", Integer.class));
    }

    @Test
    void separateFileEntriesCanHaveSeparateAssetsForTheSameContent() {
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, current_content_id, size_bytes,
                    observation_revision, modified_time_epoch_second, modified_time_nano, first_seen_at_ms, last_seen_at_ms)
                SELECT 8, location_identity_status, current_content_id, size_bytes, observation_revision,
                    modified_time_epoch_second, modified_time_nano, first_seen_at_ms, last_seen_at_ms
                FROM file_entry WHERE id = 7
                """);
        var first = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 10));
        var secondEvidence = new PreviewSourceEvidence(8, 11, 3, 42, 1700000000, 123456789);
        var second = assets.insert(asset(secondEvidence, PreviewKind.SMALL_THUMBNAIL, definition(), 10));
        assertNotEquals(first.assetKey(), second.assetKey());
        assertEquals(second, assets.findCurrent(8, definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
    }
}
