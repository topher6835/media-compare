package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.analysis.ContentHashIntegrityException;
import io.github.topher6835.mediacompare.analysis.ContentHashWriter;
import io.github.topher6835.mediacompare.analysis.ContentHashingService;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.StaleContentHashException;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ContentAssignmentService;
import io.github.topher6835.mediacompare.scan.ReconciliationService;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class ContentHashingApiTests {

    private static final String KNOWN_BYTES = "identical bytes";

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private ScanRepository scanRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ScanRunService scanRunService;

    @Autowired
    private ScanExecutionService scanExecutionService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private ContentAssignmentService contentAssignmentService;

    @Autowired
    private ContentHashingService contentHashingService;

    @Autowired
    private ContentHashWriter contentHashWriter;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
        jdbcTemplate.update("DELETE FROM file_entry");
        jdbcTemplate.update("DELETE FROM scan_run_source");
        jdbcTemplate.update("DELETE FROM working_set_content");
        jdbcTemplate.update("DELETE FROM analysis_record");
        jdbcTemplate.update("DELETE FROM job");
        jdbcTemplate.update("DELETE FROM scan_run");
        jdbcTemplate.update("DELETE FROM working_set");
        jdbcTemplate.update("DELETE FROM content_record");
        jdbcTemplate.update("DELETE FROM source");
    }

    @Test
    void hashesActualBytesWithReusableProvenanceWithoutMutatingCatalogOrExecution() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("hashes"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path firstPath = Files.writeString(nested.resolve("first.dat"), KNOWN_BYTES);
        Path secondPath = Files.writeString(root.resolve("second.dat"), KNOWN_BYTES);
        FileTime sharedModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_000_000));
        Files.setLastModifiedTime(firstPath, sharedModifiedTime);
        Files.setLastModifiedTime(secondPath, sharedModifiedTime);
        CompletedScan scan = createCompletedAssignedScan(root);
        FileEntry firstBefore = requireFile(scan.source(), "nested/first.dat");
        FileEntry secondBefore = requireFile(scan.source(), "second.dat");
        DurableExecution executionBefore = durableExecution(scan);
        long contentCountBefore = countRows("content_record");

        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanRunId").value(scan.scanRun().id()))
                .andExpect(jsonPath("$.hashedCount").value(2))
                .andExpect(jsonPath("$.cachedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0));

        String expectedDigest = sha256(KNOWN_BYTES);
        AnalysisRecord firstAnalysis = requireSha256Analysis(firstBefore.currentContentId());
        AnalysisRecord secondAnalysis = requireSha256Analysis(secondBefore.currentContentId());
        assertNotEquals(firstBefore.currentContentId(), secondBefore.currentContentId());
        assertNotEquals(firstAnalysis.id(), secondAnalysis.id());
        assertCompletedProvenance(firstAnalysis);
        assertCompletedProvenance(secondAnalysis);

        ContentHash firstHash = analysisRepository.findContentHash(firstAnalysis.id()).orElseThrow();
        ContentHash secondHash = analysisRepository.findContentHash(secondAnalysis.id()).orElseThrow();
        assertEquals(Sha256AnalysisDefinition.ALGORITHM, firstHash.algorithm());
        assertEquals(expectedDigest, firstHash.digestHex());
        assertEquals(expectedDigest, secondHash.digestHex());
        assertTrue(firstHash.digestHex().matches("[0-9a-f]{64}"));

        assertEquals(firstBefore, requireFile(scan.source(), "nested/first.dat"));
        assertEquals(secondBefore, requireFile(scan.source(), "second.dat"));
        assertEquals(contentCountBefore, countRows("content_record"));
        assertEquals(executionBefore, durableExecution(scan));
        assertEquals(1, countRows("job"));
        assertEquals(2, countRows("job_stage"));
        assertEquals(2, countRows("analysis_record"));
        assertEquals(2, countRows("content_hash"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_record WHERE analysis_type <> 'CONTENT_HASH'", Long.class));

        Files.delete(firstPath);
        Files.delete(secondPath);
        Files.delete(nested);
        Files.delete(root);
        jdbcTemplate.update("""
                UPDATE source
                SET root_path = ?, root_path_key = ?, location_revision = location_revision + 1
                WHERE id = ?
                """, temporaryDirectory.resolve("unavailable").toString(),
                temporaryDirectory.resolve("unavailable").toString(), scan.source().id());

        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(0))
                .andExpect(jsonPath("$.cachedCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0));
        assertEquals(2, countRows("analysis_record"));
        assertEquals(2, countRows("content_hash"));
    }

    @Test
    void hashesOnlyPresentAssignedEntriesFromTheExactCompletedTraversal() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("scope"));
        Files.writeString(root.resolve("eligible.dat"), "eligible");
        CompletedScan scan = createCompletedAssignedScan(root);
        ScanRunSource scanRunSource = scan.sources().getFirst();
        Source outsideSource = insertSource("Outside", temporaryDirectory.resolve("outside"));

        insertCatalogOnlyFile(scan.source(), "missing.dat", "MISSING", newContent(1),
                scanRunSource.id(), scanRunSource.completedGeneration());
        insertCatalogOnlyFile(scan.source(), "stale.dat", "PRESENT", newContent(1),
                scanRunSource.id(), 99L);
        insertCatalogOnlyFile(outsideSource, "outside.dat", "PRESENT", newContent(1), null, null);
        insertCatalogOnlyFile(scan.source(), "unassigned.dat", "PRESENT", null,
                scanRunSource.id(), scanRunSource.completedGeneration());

        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(1))
                .andExpect(jsonPath("$.cachedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0));

        assertEquals(1, countRows("analysis_record"));
        assertEquals(1, countRows("content_hash"));
    }

    @ParameterizedTest(name = "classifies uncached candidate: {0}")
    @MethodSource("filesystemScenarios")
    void classifiesFilesystemAndSourceEvidenceWithoutPublishing(
            String description, String mutation, long expectedSkipped, long expectedFailed) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("evidence"));
        Path file = Files.writeString(root.resolve("candidate.dat"), "same-size");
        CompletedScan scan = createCompletedAssignedScan(root);

        switch (mutation) {
            case "missing" -> Files.delete(file);
            case "size" -> Files.writeString(file, "different-size");
            case "mtime" -> Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(1_800_000_000L)));
            case "source" -> jdbcTemplate.update(
                    "UPDATE source SET location_revision = location_revision + 1 WHERE id = ?", scan.source().id());
            case "path" -> jdbcTemplate.update(
                    "UPDATE file_entry SET relative_path = '../outside.dat' WHERE source_id = ?",
                    scan.source().id());
            default -> throw new IllegalArgumentException("Unknown mutation " + mutation);
        }

        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(0))
                .andExpect(jsonPath("$.cachedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(expectedSkipped))
                .andExpect(jsonPath("$.failedCount").value(expectedFailed));
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    static Stream<Object[]> filesystemScenarios() {
        return Stream.of(
                new Object[] { "missing file is failed", "missing", 0L, 1L },
                new Object[] { "live size mismatch is skipped", "size", 1L, 0L },
                new Object[] { "live mtime mismatch is skipped", "mtime", 1L, 0L },
                new Object[] { "Source revision mismatch is skipped", "source", 1L, 0L },
                new Object[] { "unsafe portable path is skipped", "path", 1L, 0L });
    }

    @Test
    void doesNotFollowCandidateSymbolicLinkWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("symlink"));
        Path file = Files.writeString(root.resolve("candidate.dat"), "candidate");
        Path target = Files.writeString(temporaryDirectory.resolve("target.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, target);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertUnsafeCandidateSkipped(scan);
    }

    @Test
    void doesNotFollowIntermediateDirectorySymbolicLinkWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("symlink-parent"));
        Path directory = Files.createDirectory(root.resolve("nested"));
        Path file = Files.writeString(directory.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        Path replacement = Files.createDirectory(temporaryDirectory.resolve("replacement"));
        Files.writeString(replacement.resolve("candidate.dat"), "candidate");
        Files.delete(file);
        Files.delete(directory);
        try {
            Files.createSymbolicLink(directory, replacement);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertUnsafeCandidateSkipped(scan);
    }

    @Test
    void doesNotFollowSourceRootSymbolicLinkWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("symlink-root"));
        Path file = Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        Path replacement = Files.createDirectory(temporaryDirectory.resolve("replacement-root"));
        Files.writeString(replacement.resolve("candidate.dat"), "candidate");
        Files.delete(file);
        Files.delete(root);
        try {
            Files.createSymbolicLink(root, replacement);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertUnsafeCandidateSkipped(scan);
    }

    @Test
    void existingIntermediateNonDirectoryIsSkippedAsUnsafe() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("non-directory-parent"));
        Path directory = Files.createDirectory(root.resolve("nested"));
        Path file = Files.writeString(directory.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        Files.delete(file);
        Files.delete(directory);
        Files.writeString(root.resolve("nested"), "not a directory");

        assertUnsafeCandidateSkipped(scan);
    }

    @Test
    void existingNonRegularCandidateIsSkippedAsUnsafe() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("non-regular-candidate"));
        Path file = Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        Files.delete(file);
        Files.createDirectory(file);

        assertUnsafeCandidateSkipped(scan);
    }

    @ParameterizedTest(name = "rejects stale publication: {0}")
    @MethodSource("publicationMutations")
    void publicationGuardRejectsStaleDatabaseEvidenceWithoutArtifacts(
            String description, String mutation) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("publication"));
        Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        ScanRunSource scanRunSource = scan.sources().getFirst();
        ContentHashCandidate candidate = catalogRepository.findContentHashCandidates(
                scanRunSource.id(), scanRunSource.completedGeneration(), 0, 250).getFirst();

        switch (mutation) {
            case "revision" -> updateFile(candidate, "observation_revision = observation_revision + 1");
            case "content" -> {
                ContentRecord replacement = newContent(candidate.sizeBytes());
                jdbcTemplate.update("UPDATE file_entry SET current_content_id = ? WHERE id = ?",
                        replacement.id(), candidate.fileEntryId());
            }
            case "missing" -> updateFile(candidate, "presence_status = 'MISSING'");
            case "size" -> updateFile(candidate, "size_bytes = size_bytes + 1");
            case "mtime-second" -> updateFile(candidate,
                    "modified_time_epoch_second = modified_time_epoch_second + 1");
            case "mtime-nano" -> updateFile(candidate,
                    "modified_time_nano = (modified_time_nano + 1) % 1000000000");
            case "source" -> jdbcTemplate.update(
                    "UPDATE source SET location_revision = location_revision + 1 WHERE id = ?",
                    candidate.sourceId());
            default -> throw new IllegalArgumentException("Unknown mutation " + mutation);
        }

        long analysisCountBefore = countRows("analysis_record");
        long hashCountBefore = countRows("content_hash");
        assertThrows(StaleContentHashException.class,
                () -> contentHashWriter.publish(candidate, sha256("candidate"), 10, 20));
        assertEquals(analysisCountBefore, countRows("analysis_record"));
        assertEquals(hashCountBefore, countRows("content_hash"));
    }

    static Stream<Object[]> publicationMutations() {
        return Stream.of(
                new Object[] { "observation revision changed", "revision" },
                new Object[] { "ContentRecord association changed", "content" },
                new Object[] { "FileEntry became MISSING", "missing" },
                new Object[] { "database size changed", "size" },
                new Object[] { "database mtime second changed", "mtime-second" },
                new Object[] { "database mtime nanosecond changed", "mtime-nano" },
                new Object[] { "Source location revision changed", "source" });
    }

    @Test
    void publicationAllowsOnlyLastSeenTraversalIdentityToChange() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("later-traversal"));
        Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        ScanRunSource scanRunSource = scan.sources().getFirst();
        ContentHashCandidate candidate = catalogRepository.findContentHashCandidates(
                scanRunSource.id(), scanRunSource.completedGeneration(), 0, 250).getFirst();
        jdbcTemplate.update("""
                UPDATE file_entry
                SET last_seen_scan_run_source_id = NULL, last_seen_traversal_generation = NULL
                WHERE id = ?
                """, candidate.fileEntryId());

        contentHashWriter.publish(candidate, sha256("candidate"), 10, 20);

        AnalysisRecord analysisRecord = requireSha256Analysis(candidate.contentRecordId());
        assertEquals(sha256("candidate"),
                analysisRepository.findContentHash(analysisRecord.id()).orElseThrow().digestHex());
    }

    @Test
    void publicationRollsBackAnalysisRecordWhenContentHashInsertFails() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("atomic-publication"));
        Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        ScanRunSource scanRunSource = scan.sources().getFirst();
        ContentHashCandidate candidate = catalogRepository.findContentHashCandidates(
                scanRunSource.id(), scanRunSource.completedGeneration(), 0, 250).getFirst();
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_content_hash_insert
                BEFORE INSERT ON content_hash
                BEGIN
                    SELECT RAISE(ABORT, 'simulated ContentHash insert failure');
                END
                """);

        try {
            assertThrows(DataAccessException.class,
                    () -> contentHashWriter.publish(candidate, sha256("candidate"), 10, 20));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_content_hash_insert");
        }

        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    @ParameterizedTest(name = "detects invalid completed cache: {0}")
    @MethodSource("invalidCacheArtifacts")
    void completedCacheRequiresAValidSpecializedHash(String description, String artifact) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("integrity"));
        Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        FileEntry entry = requireFile(scan.source(), "candidate.dat");
        AnalysisRecord analysisRecord = analysisRepository.insert(completedAnalysis(entry.currentContentId(), 10, 20));
        if ("algorithm".equals(artifact)) {
            analysisRepository.insert(new ContentHash(analysisRecord.id(), "SHA-1", sha256("candidate")));
        } else if ("digest".equals(artifact)) {
            analysisRepository.insert(new ContentHash(
                    analysisRecord.id(), Sha256AnalysisDefinition.ALGORITHM, "INVALID"));
        }

        assertThrows(ContentHashIntegrityException.class,
                () -> contentHashingService.hash(scan.scanRun().id()));
        assertEquals(1, countRows("analysis_record"));
        assertEquals("missing".equals(artifact) ? 0 : 1, countRows("content_hash"));
    }

    static Stream<Object[]> invalidCacheArtifacts() {
        return Stream.of(
                new Object[] { "missing ContentHash", "missing" },
                new Object[] { "wrong algorithm", "algorithm" },
                new Object[] { "malformed digest", "digest" });
    }

    @Test
    void incompleteExactCacheEntryIsBlockedWithoutFilesystemOrDatabaseMutation() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("blocked"));
        Files.writeString(root.resolve("candidate.dat"), "candidate");
        CompletedScan scan = createCompletedAssignedScan(root);
        FileEntry entry = requireFile(scan.source(), "candidate.dat");
        AnalysisRecord pending = completedAnalysis(entry.currentContentId(), 10, 20);
        analysisRepository.insert(new AnalysisRecord(
                null, pending.contentRecordId(), pending.analysisType(), pending.analyzerId(),
                pending.analyzerVersion(), pending.configurationVersion(), pending.configurationHash(),
                pending.configurationJson(), null, "RUNNING", 1, 10, 10L, null, null));
        Files.delete(root.resolve("candidate.dat"));
        Files.delete(root);

        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(0))
                .andExpect(jsonPath("$.cachedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(1));
        assertEquals(1, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    @Test
    void unknownScanRunAndMissingExecutionReturnNotFound() throws Exception {
        mockMvc.perform(post(hashingPath(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());

        Source source = insertSource("No execution", temporaryDirectory.resolve("missing-execution"));
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        mockMvc.perform(post(hashingPath(scanRun.scanRun().id())))
                .andExpect(status().isNotFound());
    }

    @Test
    void incompleteLifecycleReturnsConflictWithoutMutation() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("conflict"));
        CompletedScan scan = createCompletedAssignedScan(root);
        jdbcTemplate.update("UPDATE scan_run_source SET status = 'DISCOVERED' WHERE id = ?",
                scan.sources().getFirst().id());
        DurableState before = durableState();

        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isConflict());

        assertEquals(before, durableState());
    }

    @Test
    void writerHasARealTransactionProxyAndServiceDoesNotWrapFileReadsInATransaction() throws Exception {
        assertTrue(AopUtils.isAopProxy(contentHashWriter));
        assertNotNull(ContentHashWriter.class
                .getMethod("publish", ContentHashCandidate.class, String.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        assertNull(ContentHashingService.class.getMethod("hash", long.class).getAnnotation(Transactional.class));
    }

    @Test
    void configurationHashIsTheSha256OfExactUtf8ConfigurationJson() throws Exception {
        assertEquals(sha256(Sha256AnalysisDefinition.CONFIGURATION_JSON),
                Sha256AnalysisDefinition.CONFIGURATION_HASH);
    }

    private CompletedScan createCompletedAssignedScan(Path root) {
        Source source = insertSource("Source", root);
        ScanRunDetails requested = scanRunService.create(List.of(source.id()));
        Job job = scanExecutionService.create(requested.scanRun().id()).job();
        scanExecutionService.executeDiscovery(requested.scanRun().id());
        reconciliationService.execute(requested.scanRun().id());
        contentAssignmentService.assign(requested.scanRun().id());
        return new CompletedScan(
                source,
                scanRepository.findScanRunById(requested.scanRun().id()).orElseThrow(),
                scanRepository.findScanRunSourcesByScanRunId(requested.scanRun().id()),
                jobRepository.findJobById(job.id()).orElseThrow(),
                jobRepository.findJobStagesByJobId(job.id()));
    }

    private Source insertSource(String name, Path root) {
        String rootPath = root.toAbsolutePath().toString();
        return catalogRepository.insert(new Source(null, name, rootPath, rootPath, 0, 1, 1));
    }

    private ContentRecord newContent(long size) {
        return catalogRepository.insert(new ContentRecord(null, size, 1));
    }

    private FileEntry insertCatalogOnlyFile(Source source, String path, String presence,
            ContentRecord content, Long scanRunSourceId, Long generation) {
        return catalogRepository.insert(new FileEntry(
                null, source.id(), path, path, content == null ? null : content.id(), presence, 1,
                100L, 200, 0, 1, 1, scanRunSourceId, generation));
    }

    private AnalysisRecord requireSha256Analysis(long contentRecordId) {
        return analysisRepository.findAnalysisRecordByCacheKey(
                contentRecordId,
                Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH).orElseThrow();
    }

    private AnalysisRecord completedAnalysis(long contentRecordId, long startedAtMs, long finishedAtMs) {
        return new AnalysisRecord(
                null, contentRecordId, Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID, Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION, Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON, null, "COMPLETED", 1,
                startedAtMs, startedAtMs, finishedAtMs, null);
    }

    private void assertCompletedProvenance(AnalysisRecord analysisRecord) {
        assertEquals(Sha256AnalysisDefinition.ANALYSIS_TYPE, analysisRecord.analysisType());
        assertEquals(Sha256AnalysisDefinition.ANALYZER_ID, analysisRecord.analyzerId());
        assertEquals(Sha256AnalysisDefinition.ANALYZER_VERSION, analysisRecord.analyzerVersion());
        assertEquals(Sha256AnalysisDefinition.CONFIGURATION_VERSION, analysisRecord.configurationVersion());
        assertEquals(Sha256AnalysisDefinition.CONFIGURATION_HASH, analysisRecord.configurationHash());
        assertEquals(Sha256AnalysisDefinition.CONFIGURATION_JSON, analysisRecord.configurationJson());
        assertNull(analysisRecord.resultJson());
        assertEquals("COMPLETED", analysisRecord.status());
        assertEquals(1, analysisRecord.attemptCount());
        assertEquals(analysisRecord.createdAtMs(), analysisRecord.startedAtMs());
        assertNotNull(analysisRecord.finishedAtMs());
        assertTrue(analysisRecord.finishedAtMs() >= analysisRecord.startedAtMs());
        assertNull(analysisRecord.errorMessage());
    }

    private FileEntry requireFile(Source source, String pathKey) {
        return catalogRepository.findFileEntryBySourceIdAndPathKey(source.id(), pathKey).orElseThrow();
    }

    private void assertUnsafeCandidateSkipped(CompletedScan scan) throws Exception {
        mockMvc.perform(post(hashingPath(scan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(0))
                .andExpect(jsonPath("$.cachedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0));
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    private void updateFile(ContentHashCandidate candidate, String assignment) {
        jdbcTemplate.update("UPDATE file_entry SET " + assignment + " WHERE id = ?", candidate.fileEntryId());
    }

    private DurableExecution durableExecution(CompletedScan scan) {
        return new DurableExecution(
                scanRepository.findScanRunById(scan.scanRun().id()).orElseThrow(),
                scanRepository.findScanRunSourcesByScanRunId(scan.scanRun().id()),
                jobRepository.findJobById(scan.job().id()).orElseThrow(),
                jobRepository.findJobStagesByJobId(scan.job().id()));
    }

    private DurableState durableState() {
        return new DurableState(
                jdbcTemplate.queryForList("SELECT * FROM scan_run ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM scan_run_source ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM job ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM job_stage ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM file_entry ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM content_record ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM analysis_record ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM content_hash ORDER BY analysis_record_id"));
    }

    private long countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String hashingPath(long scanRunId) {
        return "/api/scan-runs/" + scanRunId + "/content-hashing";
    }

    private record CompletedScan(
            Source source,
            ScanRun scanRun,
            List<ScanRunSource> sources,
            Job job,
            List<JobStage> stages) {
    }

    private record DurableExecution(
            ScanRun scanRun,
            List<ScanRunSource> sources,
            Job job,
            List<JobStage> stages) {
    }

    private record DurableState(
            List<Map<String, Object>> scanRuns,
            List<Map<String, Object>> scanRunSources,
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> stages,
            List<Map<String, Object>> fileEntries,
            List<Map<String, Object>> contentRecords,
            List<Map<String, Object>> analyses,
            List<Map<String, Object>> hashes) {
    }
}
