package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.matching.ExactDuplicateIntegrityException;
import io.github.topher6835.mediacompare.matching.ExactDuplicateService;
import io.github.topher6835.mediacompare.matching.ExactHashRelationshipEdge;
import io.github.topher6835.mediacompare.matching.ExactHashRelationshipProjection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipGroupService;
import io.github.topher6835.mediacompare.matching.MediaRelationshipSelection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:exact-projection-tests-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExactHashRelationshipProjectionTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository catalog;
    @Autowired private AnalysisRepository analyses;
    @Autowired private ExactHashRelationshipProjection projection;
    @Autowired private ExactDuplicateService duplicates;
    @Autowired private MediaRelationshipGroupService groups;
    @TempDir Path temporaryDirectory;

    @AfterEach
    void clearRows() {
        for (String table : List.of("media_relationship", "analysis_record", "content_hash")) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                jdbc.execute("DROP TRIGGER IF EXISTS forbid_" + table + "_" + operation);
            }
        }
        for (String table : List.of("media_relationship", "source_membership", "file_entry", "source",
                "content_hash", "analysis_record", "content_record")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 12, 100})
    void emitsExactlyNMinusOneEdgesWithTheSmallestContentAnchorWithoutPhysicalOccurrences(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(content(42));
        }
        // Artifact insertion order must not determine the anchor or edge order.
        for (long id : ids.reversed()) {
            hash(id, digest(10));
        }
        List<ExactHashRelationshipEdge> expected = ids.stream().skip(1)
                .map(id -> new ExactHashRelationshipEdge(ids.getFirst(), id)).toList();
        assertEquals(count - 1, expected.size());
        assertEquals(expected, projection.project());
        assertEquals(expected, projection.project());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM media_relationship", Long.class));
    }

    @Test
    void separatesDigestsOmitsSingletonsAndOrdersByDigestBeforeEndpointIds() {
        long a = hashed(digest(20));
        long b = hashed(digest(20));
        long c = hashed(digest(10));
        long d = hashed(digest(10));
        long e = hashed(digest(10));
        hashed(digest(30));
        assertEquals(List.of(new ExactHashRelationshipEdge(c, d), new ExactHashRelationshipEdge(c, e),
                new ExactHashRelationshipEdge(a, b)), projection.project());
        assertEquals(List.of(List.of(a, b), List.of(c, d, e)), groups.group(exactSelection()));
    }

    @Test
    void missingAndRetiredOccurrencesAndUnavailableUnboundSourceDoNotLimitEquality() {
        long a = hashed(digest(10));
        long b = hashed(digest(10));
        String root = temporaryDirectory.resolve("unavailable").toString();
        long source = catalog.insert(new Source(null, "Offline", root, root, 0, 1, 1)).id();
        FileEntry entry = catalog.insert(new FileEntry(null, "UNRESOLVED", null, null, null, a,
                42L, 100L, 0, null, 0, 1, 1));
        jdbc.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path, path_key,
                    applicability_status, presence_status, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms)
                VALUES (?, ?, 'missing.bin', 'missing.bin', 'ACTIVE', 'MISSING', 0, 1, 1)
                """, source, entry.id());
        List<ExactHashRelationshipEdge> expected = List.of(new ExactHashRelationshipEdge(a, b));
        assertEquals(expected, projection.project());
        jdbc.update("UPDATE source_membership SET applicability_status = 'RETIRED'");
        assertEquals(expected, projection.project());
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        assertEquals(expected, projection.project());
    }

    @ParameterizedTest
    @ValueSource(strings = {"analysis_type", "analyzer_id", "analyzer_version", "configuration_version",
            "configuration_hash", "FAILED", "PENDING", "RUNNING"})
    void incompatibleOrUncompletedAnalysesNeverParticipate(String difference) {
        long a = hashed(digest(10));
        long b = hashed(digest(10));
        long excluded = hashed(digest(10));
        if (List.of("FAILED", "PENDING", "RUNNING").contains(difference)) {
            jdbc.update("UPDATE analysis_record SET status = ? WHERE content_record_id = ?", difference, excluded);
        } else {
            Object value = "configuration_version".equals(difference) ? 2L : "old";
            jdbc.update("UPDATE analysis_record SET " + difference + " = ? WHERE content_record_id = ?",
                    value, excluded);
        }
        // Even corrupt artifacts outside the current completed definition must be ignored.
        jdbc.update("UPDATE content_hash SET algorithm = 'SHA-1', digest_hex = 'invalid' "
                + "WHERE analysis_record_id IN (SELECT id FROM analysis_record WHERE content_record_id = ?)", excluded);
        assertEquals(List.of(new ExactHashRelationshipEdge(a, b)), projection.project());
    }

    @ParameterizedTest
    @ValueSource(strings = {"configuration", "configuration-spelling", "missing", "algorithm",
            "short-digest", "non-hex-digest", "uppercase-digest", "size"})
    void invalidCurrentArtifactsFailTheWholeProjectionWithExistingIntegritySemantics(String mutation) {
        hashed(digest(10));
        hashed(digest(10));
        long badContent = content("size".equals(mutation) ? 43 : 42);
        long badAnalysis = hash(badContent, "size".equals(mutation) ? digest(10) : digest(20));
        switch (mutation) {
            case "configuration", "configuration-spelling" -> jdbc.update(
                    "UPDATE analysis_record SET configuration_json = ? WHERE id = ?",
                    "configuration".equals(mutation) ? "{\"unexpected\":true}" : " {} ", badAnalysis);
            case "missing" -> jdbc.update("DELETE FROM content_hash WHERE analysis_record_id = ?", badAnalysis);
            case "algorithm" -> jdbc.update("UPDATE content_hash SET algorithm = 'SHA-1' WHERE analysis_record_id = ?",
                    badAnalysis);
            case "short-digest", "non-hex-digest", "uppercase-digest" -> jdbc.update(
                    "UPDATE content_hash SET digest_hex = ? WHERE analysis_record_id = ?",
                    switch (mutation) {
                        case "short-digest" -> "abc123";
                        case "non-hex-digest" -> "g".repeat(64);
                        default -> "A".repeat(64);
                    }, badAnalysis);
            case "size" -> { }
            default -> throw new IllegalArgumentException(mutation);
        }
        Map<String, List<Map<String, Object>>> before = artifacts();
        ExactDuplicateIntegrityException error = assertThrows(ExactDuplicateIntegrityException.class, projection::project);
        assertEquals(error.getMessage(), assertThrows(ExactDuplicateIntegrityException.class,
                () -> duplicates.findGroups(null, null)).getMessage());
        assertThrows(ExactDuplicateIntegrityException.class, () -> groups.group(exactSelection()));
        assertEquals(before, artifacts());
        // An unselected SHA producer must never trigger even its integrity query.
        assertEquals(List.of(), groups.group(new MediaRelationshipSelection(List.of())));
    }

    @Test
    void repeatedProjectionAndGroupingNeverInsertUpdateOrDeleteAnyAuthorityOrRelationshipRow() {
        long a = hashed(digest(10));
        long b = hashed(digest(10));
        long c = hashed(digest(10));
        Map<String, List<Map<String, Object>>> before = artifacts();
        // Abort every possible write, including writes that might otherwise leave the same final snapshot.
        for (String table : before.keySet()) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                jdbc.execute("CREATE TRIGGER forbid_" + table + "_" + operation + " BEFORE " + operation
                        + " ON " + table + " BEGIN SELECT RAISE(ABORT, 'projection must be read-only'); END");
            }
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of(new ExactHashRelationshipEdge(a, b), new ExactHashRelationshipEdge(a, c)),
                    projection.project());
            assertEquals(List.of(List.of(a, b, c)), groups.group(exactSelection()));
        }
        assertEquals(before, artifacts());
        assertEquals(List.of(), before.get("media_relationship"));
    }

    private Map<String, List<Map<String, Object>>> artifacts() {
        return Map.of("media_relationship", jdbc.queryForList("SELECT * FROM media_relationship ORDER BY id"),
                "analysis_record", jdbc.queryForList("SELECT * FROM analysis_record ORDER BY id"),
                "content_hash", jdbc.queryForList("SELECT * FROM content_hash ORDER BY analysis_record_id"));
    }

    private long hashed(String digest) {
        long id = content(42);
        hash(id, digest);
        return id;
    }

    private long content(long size) {
        return catalog.insert(new ContentRecord(null, size, 1)).id();
    }

    private long hash(long contentId, String digest) {
        AnalysisRecord analysis = analyses.insert(new AnalysisRecord(null, contentId,
                Sha256AnalysisDefinition.ANALYSIS_TYPE, Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION, Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH, Sha256AnalysisDefinition.CONFIGURATION_JSON,
                null, "COMPLETED", 1, 1, 1L, 2L, null));
        analyses.insert(new ContentHash(analysis.id(), Sha256AnalysisDefinition.ALGORITHM, digest));
        return analysis.id();
    }

    private static MediaRelationshipSelection exactSelection() {
        return new MediaRelationshipSelection(List.of(ExactHashRelationshipProjection.DEFINITION));
    }

    private static String digest(long value) {
        return "%064x".formatted(value);
    }
}
