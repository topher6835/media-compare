package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.Source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LocationContextPersistenceTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LocationContextRepository locationContexts;

    @Autowired
    private CatalogRepository catalog;

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM location_context");
    }

    @Test
    void insertsAndReadsReviewRequiredContextWithNullableEvidenceAndExactTextId() {
        LocationContext context = context(
                "2f610e9d-4d27-46fc-88b1-7e53ca880899",
                "/Volumes/Media",
                "unix:/Volumes/Media",
                LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED,
                3,
                null);

        assertEquals(context, locationContexts.insert(context));
        assertEquals(context, locationContexts.findById(context.id()).orElseThrow());
        assertEquals(context, locationContexts.findActiveByAnchorLocationKey(context.anchorLocationKey())
                .orElseThrow());
        assertNull(locationContexts.findById(context.id()).orElseThrow().continuityEvidenceJson());
    }

    @Test
    void acceptedContinuityRequiresEvidenceInDomainAndDatabase() {
        assertThrows(IllegalArgumentException.class, () -> context(
                "00000000-0000-0000-0000-000000000001", "/accepted", "accepted", LifecycleStatus.ACTIVE,
                ContinuityStatus.ACCEPTED, 0, null));

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO location_context (
                    id, anchor_location_path, anchor_location_key, lifecycle_status,
                    continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms
                ) VALUES ('00000000-0000-0000-0000-000000000002', '/invalid', 'invalid', 'ACTIVE', 'ACCEPTED', 0, NULL, 1, 1)
                """));

        LocationContext accepted = context(
                "00000000-0000-0000-0000-000000000003", "/accepted", "accepted", LifecycleStatus.ACTIVE,
                ContinuityStatus.ACCEPTED, 0, "{\"profileVersion\":1}");
        locationContexts.insert(accepted);
        assertEquals(accepted, locationContexts.findById(accepted.id()).orElseThrow());
    }

    @Test
    void activeAnchorIdentityIsBinaryAndUniqueOnlyAmongActiveContexts() {
        LocationContext activeUpper = context(
                "00000000-0000-0000-0000-000000000011", "/Drive", "Drive", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null);
        LocationContext activeLower = context(
                "00000000-0000-0000-0000-000000000012", "/drive", "drive", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null);
        LocationContext retiredUpper = context(
                "00000000-0000-0000-0000-000000000013", "/Drive", "Drive", LifecycleStatus.RETIRED,
                ContinuityStatus.REVIEW_REQUIRED, 1, null);

        locationContexts.insert(activeUpper);
        locationContexts.insert(activeLower);
        locationContexts.insert(retiredUpper);

        assertEquals(activeUpper, locationContexts.findActiveByAnchorLocationKey("Drive").orElseThrow());
        assertEquals(activeLower, locationContexts.findActiveByAnchorLocationKey("drive").orElseThrow());
        assertThrows(DataAccessException.class, () -> locationContexts.insert(context(
                "00000000-0000-0000-0000-000000000014", "/other", "Drive", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null)));
    }

    @Test
    void sourceBindingFieldsRoundTripAndRestrictContextDeletion() {
        LocationContext context = locationContexts.insert(context(
                "00000000-0000-0000-0000-000000000021", "D:\\", "windows:D:", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null));
        Source source = catalog.insert(new Source(
                null,
                "Bound fixture",
                "D:\\Media",
                "D:\\Media",
                5,
                "WINDOWS",
                context.id(),
                "{\"bindingVersion\":1}",
                10,
                11));

        assertEquals(source, catalog.findSourceById(source.id()).orElseThrow());
        assertEquals("WINDOWS", source.rootPathDialect());
        assertEquals(context.id(), source.boundLocationContextId());
        assertEquals("{\"bindingVersion\":1}", source.bindingEvidenceJson());
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM location_context WHERE id = ?", context.id()));
    }

    @Test
    void legacySourceConstructionPersistsNullBindingFields() {
        Source source = catalog.insert(new Source(
                null, "Legacy", "/legacy", "/legacy", 0, 1, 1));

        Source reloaded = catalog.findSourceById(source.id()).orElseThrow();
        assertNull(reloaded.rootPathDialect());
        assertNull(reloaded.boundLocationContextId());
        assertNull(reloaded.bindingEvidenceJson());
    }

    @Test
    void domainRejectsInvalidStructuralValues() {
        assertThrows(IllegalArgumentException.class, () -> context(
                "not-a-uuid", "/anchor", "anchor", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null));
        assertThrows(IllegalArgumentException.class, () -> context(
                "00000000-0000-0000-0000-0000000000AA", "/anchor", "anchor", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null));
        assertThrows(IllegalArgumentException.class, () -> context(
                "00000000-0000-0000-0000-000000000031", "/anchor", "anchor", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new LocationContext(
                "00000000-0000-0000-0000-000000000032", "/anchor", "anchor", LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 0, null, 2, 1));
        assertTrue(locationContexts.findById("missing").isEmpty());
    }

    private LocationContext context(String id, String path, String key, LifecycleStatus lifecycle,
            ContinuityStatus continuity, long revision, String evidence) {
        return new LocationContext(id, path, key, lifecycle, continuity, revision, evidence, 1, 2);
    }
}
