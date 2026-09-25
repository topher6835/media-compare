package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.FileObservation;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.WorkingSet;
import io.github.topher6835.mediacompare.catalog.WorkingSetContent;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PersistenceFoundationTests {

    private static final Set<String> APPLICATION_TABLES = Set.of(
            "source",
            "content_record",
            "file_entry",
            "source_membership",
            "working_set",
            "working_set_content",
            "scan_run",
            "scan_run_source",
            "job",
            "job_stage",
            "analysis_record",
            "content_hash",
            "location_context");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private ScanRepository scanRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
        jdbcTemplate.update("DELETE FROM source_membership");
        jdbcTemplate.update("DELETE FROM file_entry");
        jdbcTemplate.update("DELETE FROM scan_run_source");
        jdbcTemplate.update("DELETE FROM working_set_content");
        jdbcTemplate.update("DELETE FROM analysis_record");
        jdbcTemplate.update("DELETE FROM job");
        jdbcTemplate.update("DELETE FROM scan_run");
        jdbcTemplate.update("DELETE FROM working_set");
        jdbcTemplate.update("DELETE FROM content_record");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM location_context");
    }

    @Test
    void flywayCreatesExactlyTheThirteenApplicationTables() {
        Set<String> actualTables = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT name
                FROM sqlite_schema
                WHERE type = 'table'
                  AND name NOT LIKE 'sqlite_%'
                  AND name <> 'flyway_schema_history'
                """, String.class));

        assertEquals(APPLICATION_TABLES, actualTables);
    }

    @Test
    void foreignKeysAreEnabledOnEveryPhysicalConnectionAndRejectInvalidReferences() throws SQLException {
        try (Connection firstConnection = dataSource.getConnection();
                Connection secondConnection = dataSource.getConnection()) {
            assertEquals(1, foreignKeysSetting(firstConnection));
            assertEquals(1, foreignKeysSetting(secondConnection));
        }

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO analysis_record (
                    content_record_id, analysis_type, analyzer_id, analyzer_version,
                    configuration_version, configuration_hash, configuration_json, status,
                    attempt_count, created_at_ms
                ) VALUES (999999, 'HASH', 'sha256', '1', 1, 'config', '{}', 'PENDING', 0, 1)
                """));
    }

    @Test
    void activeMembershipPathIsUniqueWithinSourceButNotAcrossSources() {
        Source first = insertSource("First");
        Source second = insertSource("Second");
        FileEntry firstEntry = catalogRepository.insert(unresolvedEntry(10, null));
        FileEntry secondEntry = catalogRepository.insert(unresolvedEntry(10, null));
        FileEntry thirdEntry = catalogRepository.insert(unresolvedEntry(10, null));
        insertMembership(first.id(), firstEntry.id(), "Cat.jpg");
        assertThrows(DataAccessException.class,
                () -> insertMembership(first.id(), secondEntry.id(), "Cat.jpg"));
        insertMembership(second.id(), thirdEntry.id(), "Cat.jpg");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_membership", Integer.class));
    }

    @Test
    void trustedAuthorityRevisionsArePairedButHistoricalScanProvenanceMayBePartial() {
        Source source = insertSource("Historical");
        FileEntry entry = catalogRepository.insert(unresolvedEntry(10, null));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms, observed_source_location_revision)
                VALUES (?, ?, 'a', 'a', 'ACTIVE', 'PRESENT', 0, 1, 1, 0)
                """, source.id(), entry.id()));
        jdbcTemplate.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms, last_positive_traversal_generation)
                VALUES (?, ?, 'a', 'a', 'ACTIVE', 'PRESENT', 0, 1, 1, 1)
                """, source.id(), entry.id());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_membership", Integer.class));
    }

    @Test
    void historicalDiscoveryAndMissingWritersCannotMutateV6Presence() {
        Source source = insertSource("Legacy");
        assertThrows(UnsupportedOperationException.class,
                () -> catalogRepository.observeFile(new FileObservation(
                        source.id(), "a.jpg", "a.jpg", 10, 100, 25, 1, 1, 1)));
        assertThrows(UnsupportedOperationException.class,
                () -> catalogRepository.markUnseenPresentFilesMissing(source.id(), 1, 1));
    }

    @Test
    void completedGenerationAllowsNullOrACompletedGenerationAtMostTheTraversalGeneration() {
        ScanRun scanRun = insertScanRun();

        assertNotNull(insertScanRunSource(scanRun.id(), insertSource("Generation 0").id(), 0, null).id());
        assertNotNull(insertScanRunSource(scanRun.id(), insertSource("Generation 1").id(), 1, 1L).id());
        assertNotNull(insertScanRunSource(scanRun.id(), insertSource("Generation 5 partial").id(), 5, 4L).id());
        assertNotNull(insertScanRunSource(scanRun.id(), insertSource("Generation 5 complete").id(), 5, 5L).id());
    }

    @Test
    void completedGenerationRejectsNonPositiveOrFutureGenerations() {
        ScanRun scanRun = insertScanRun();

        assertThrows(DataAccessException.class,
                () -> insertScanRunSource(scanRun.id(), insertSource("Ahead of zero").id(), 0, 1L));
        assertThrows(DataAccessException.class,
                () -> insertScanRunSource(scanRun.id(), insertSource("Ahead").id(), 2, 3L));
        assertThrows(DataAccessException.class,
                () -> insertScanRunSource(scanRun.id(), insertSource("Zero").id(), 2, 0L));
        assertThrows(DataAccessException.class,
                () -> insertScanRunSource(scanRun.id(), insertSource("Negative").id(), 2, -1L));
    }

    @Test
    void workingSetMembershipCannotBeDuplicated() {
        WorkingSet workingSet = catalogRepository.insert(new WorkingSet(null, "Review", 1, 1));
        ContentRecord contentRecord = insertContentRecord(10);
        WorkingSetContent membership = new WorkingSetContent(workingSet.id(), contentRecord.id(), 1);

        catalogRepository.insert(membership);

        assertThrows(DataAccessException.class, () -> catalogRepository.insert(membership));
    }

    @Test
    void jobHasOnlyOneAggregateStagePerType() {
        Job job = jobRepository.insert(new Job(null, null, "SCAN", 1, "PENDING", null, 0, null, 0, 1, null,
                null, null));
        JobStage stage = new JobStage(null, job.id(), "DISCOVERY", null, "PENDING", 0, null, 0, 1, null, null,
                null);

        jobRepository.insert(stage);

        assertThrows(DataAccessException.class, () -> jobRepository.insert(stage));
    }

    @Test
    void analysisRecordUsesTheCompleteReviewedCompatibilityKey() {
        ContentRecord contentRecord = insertContentRecord(10);
        AnalysisRecord original = analysisRecord(contentRecord.id(), "config-a", "{\"setting\":1}");

        analysisRepository.insert(original);

        AnalysisRecord sameIdentityDifferentJson = analysisRecord(contentRecord.id(), "config-a", "{\"setting\":2}");
        assertThrows(DataAccessException.class, () -> analysisRepository.insert(sameIdentityDifferentJson));

        AnalysisRecord changedCompatibilityKey = analysisRecord(contentRecord.id(), "config-b", "{\"setting\":2}");
        assertNotNull(analysisRepository.insert(changedCompatibilityKey).id());
    }

    @Test
    void identicalContentHashDigestsAreAllowedForDifferentAnalysisRecords() {
        ContentRecord firstContent = insertContentRecord(10);
        ContentRecord secondContent = insertContentRecord(10);
        AnalysisRecord firstAnalysis = analysisRepository.insert(analysisRecord(firstContent.id(), "config", "{}"));
        AnalysisRecord secondAnalysis = analysisRepository.insert(analysisRecord(secondContent.id(), "config", "{}"));

        analysisRepository.insert(new ContentHash(firstAnalysis.id(), "sha-256", "abc123"));
        analysisRepository.insert(new ContentHash(secondAnalysis.id(), "sha-256", "abc123"));

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM content_hash WHERE algorithm = ? AND digest_hex = ?
                """, Integer.class, "sha-256", "abc123");
        assertEquals(2, count);
    }

    @Test
    void structuralNumericAndTimestampConstraintsRejectInvalidValues() {
        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("INSERT INTO content_record (size_bytes, created_at_ms) VALUES (-1, 1)"));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO job (job_type, status, progress_completed, attempt_count, created_at_ms)
                VALUES ('SCAN', 'PENDING', -1, 0, 1)
                """));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO job (job_type, status, progress_completed, progress_total, attempt_count, created_at_ms)
                VALUES ('SCAN', 'PENDING', 0, -1, 0, 1)
                """));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update("""
                INSERT INTO job (
                    job_type, execution_version, status, progress_completed, attempt_count, created_at_ms
                ) VALUES ('SCAN', 0, 'PENDING', 0, 0, 1)
                """));
        assertThrows(DataAccessException.class, () -> insertFileEntryDirectly(10L, 1_000_000_000));
        assertThrows(DataAccessException.class, () -> insertFileEntryDirectly(10L, null));
    }

    @Test
    void repositoriesInsertAndReadThePersistenceRecords() {
        Source source = insertSource("Repository round trip");
        assertEquals(source, catalogRepository.findSourceById(source.id()).orElseThrow());

        ContentRecord contentRecord = insertContentRecord(42);
        assertEquals(contentRecord, catalogRepository.findContentRecordById(contentRecord.id()).orElseThrow());

        WorkingSet workingSet = catalogRepository.insert(new WorkingSet(null, "Review", 2, 3));
        assertEquals(workingSet, catalogRepository.findWorkingSetById(workingSet.id()).orElseThrow());
        WorkingSetContent membership = new WorkingSetContent(workingSet.id(), contentRecord.id(), 4);
        catalogRepository.insert(membership);
        assertEquals(membership,
                catalogRepository.findWorkingSetContent(workingSet.id(), contentRecord.id()).orElseThrow());

        ScanRun scanRun = scanRepository.insert(new ScanRun(null, "request-key", "INDEX", "PENDING",
                workingSet.id(), 1, "{}", 5, null, null, null));
        assertEquals(scanRun, scanRepository.findScanRunById(scanRun.id()).orElseThrow());
        ScanRunSource scanRunSource = scanRepository.insert(new ScanRunSource(null, scanRun.id(), source.id(),
                "PENDING", source.locationRevision(), 1, null, null, null, null));
        assertEquals(scanRunSource, scanRepository.findScanRunSourceById(scanRunSource.id()).orElseThrow());

        FileEntry fileEntry = catalogRepository.insert(new FileEntry(null, "UNRESOLVED", null,
                null, null, contentRecord.id(), 42, 100L, 123, "mov", 0, 6, 6));
        insertMembership(source.id(), fileEntry.id(), "Media/Clip.mov");
        assertEquals(fileEntry, catalogRepository.findFileEntryById(fileEntry.id()).orElseThrow());

        Job job = jobRepository.insert(new Job(null, scanRun.id(), "SCAN", 2, "PENDING", "DISCOVERY", 0, 10L, 0,
                7, null, null, null));
        assertEquals(job, jobRepository.findJobById(job.id()).orElseThrow());
        JobStage jobStage = jobRepository.insert(new JobStage(null, job.id(), "DISCOVERY",
                "{\"resultVersion\":1}", "PENDING", 0, 10L, 0, 8, null, null, null));
        assertEquals(jobStage, jobRepository.findJobStageById(jobStage.id()).orElseThrow());

        AnalysisRecord analysisRecord = analysisRepository.insert(analysisRecord(contentRecord.id(), "config", "{}"));
        assertEquals(analysisRecord,
                analysisRepository.findAnalysisRecordById(analysisRecord.id()).orElseThrow());
        ContentHash contentHash = new ContentHash(analysisRecord.id(), "sha-256", "abc123");
        analysisRepository.insert(contentHash);
        assertEquals(contentHash, analysisRepository.findContentHash(analysisRecord.id()).orElseThrow());
    }

    @Test
    void dependentRowsCascadeWhileCatalogReferencesRemainRestrictive() {
        ContentRecord contentRecord = insertContentRecord(10);
        WorkingSet workingSet = catalogRepository.insert(new WorkingSet(null, "Review", 1, 1));
        catalogRepository.insert(new WorkingSetContent(workingSet.id(), contentRecord.id(), 1));

        assertThrows(DataAccessException.class,
                () -> jdbcTemplate.update("DELETE FROM content_record WHERE id = ?", contentRecord.id()));

        jdbcTemplate.update("DELETE FROM working_set WHERE id = ?", workingSet.id());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM working_set_content", Integer.class));

        AnalysisRecord analysisRecord = analysisRepository.insert(analysisRecord(contentRecord.id(), "config", "{}"));
        analysisRepository.insert(new ContentHash(analysisRecord.id(), "sha-256", "abc123"));
        jdbcTemplate.update("DELETE FROM analysis_record WHERE id = ?", analysisRecord.id());
        assertTrue(analysisRepository.findContentHash(analysisRecord.id()).isEmpty());
    }

    private Source insertSource(String name) {
        return catalogRepository.insert(new Source(null, name, "configured-root", "configured-root", 0, 1, 1));
    }

    private ContentRecord insertContentRecord(long sizeBytes) {
        return catalogRepository.insert(new ContentRecord(null, sizeBytes, 1));
    }

    private FileEntry unresolvedEntry(long sizeBytes, Long contentId) {
        return new FileEntry(null, "UNRESOLVED", null, null, null, contentId,
                sizeBytes, 100L, 25, null, 0, 1, 1);
    }

    private void insertMembership(long sourceId, long fileEntryId, String path) {
        jdbcTemplate.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'PRESENT', 0, 1, 1)
                """, sourceId, fileEntryId, path, path);
    }

    private ScanRun insertScanRun() {
        return scanRepository.insert(new ScanRun(null, null, "INDEX", "PENDING", null, 1, "{}", 1,
                null, null, null));
    }

    private ScanRunSource insertScanRunSource(long scanRunId, long sourceId, long traversalGeneration,
            Long completedGeneration) {
        return scanRepository.insert(new ScanRunSource(null, scanRunId, sourceId, "PENDING", 0,
                traversalGeneration, completedGeneration, null, null, null));
    }

    private AnalysisRecord analysisRecord(long contentRecordId, String configurationHash, String configurationJson) {
        return new AnalysisRecord(null, contentRecordId, "EXACT_HASH", "java-message-digest", "1",
                1, configurationHash, configurationJson, null, "PENDING", 0, 1, null, null, null);
    }

    private void insertFileEntryDirectly(Long modifiedSecond, Integer modifiedNano) {
        jdbcTemplate.update("""
                INSERT INTO file_entry (
                    location_identity_status, size_bytes,
                    modified_time_epoch_second, modified_time_nano, first_seen_at_ms, last_seen_at_ms
                ) VALUES ('UNRESOLVED', 1, ?, ?, 1, 1)
                """, modifiedSecond, modifiedNano);
    }

    private int foreignKeysSetting(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
            return resultSet.getInt(1);
        }
    }
}
