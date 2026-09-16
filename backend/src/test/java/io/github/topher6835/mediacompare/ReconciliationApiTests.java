package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.DiscoveryFileWalker;
import io.github.topher6835.mediacompare.scan.ReconciliationExecutionState;
import io.github.topher6835.mediacompare.scan.ReconciliationService;
import io.github.topher6835.mediacompare.scan.ReconciliationWriter;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationApiTests {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private ScanRepository scanRepository;

    @Autowired
    private ScanRunService scanRunService;

    @Autowired
    private ScanExecutionService scanExecutionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ReconciliationExecutionState reconciliationExecutionState;

    @Autowired
    private ReconciliationWriter reconciliationWriter;

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
    void reconcilesDurableObservationsAndCompletesTheScanWithoutFilesystemAvailability() throws Exception {
        Path firstRoot = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path currentFile = Files.writeString(firstRoot.resolve("current.dat"), "current");
        Path extraFile = Files.writeString(firstRoot.resolve("extra.dat"), "extra");
        Path reappearedFile = Files.writeString(firstRoot.resolve("reappeared.dat"), "back");
        Path secondRoot = Files.createDirectory(temporaryDirectory.resolve("second"));
        Path outsideRoot = temporaryDirectory.resolve("outside-not-created");
        Source firstSource = insertSource("First", firstRoot);
        Source secondSource = insertSource("Second", secondRoot);
        Source outsideSource = insertSource("Outside", outsideRoot);
        ScanRunDetails scanRun = scanRunService.create(List.of(secondSource.id(), firstSource.id()));
        Job initialJob = scanExecutionService.create(scanRun.scanRun().id()).job();
        ScanRunSource firstScanSource = sourceFor(scanRun, firstSource);

        ContentRecord knownContent = catalogRepository.insert(new ContentRecord(null, 77, 1));
        FileEntry stale = catalogRepository.insert(new FileEntry(
                null, firstSource.id(), "stale.dat", "stale.dat", knownContent.id(), "PRESENT", 77,
                123L, 456, 7, 10, 20, firstScanSource.id(), 99L));
        FileEntry nullLastSeen = catalogRepository.insert(new FileEntry(
                null, firstSource.id(), "null-last-seen.dat", "null-last-seen.dat", null, "PRESENT", 88,
                321L, 654, 4, 11, 21, null, null));
        FileEntry alreadyMissing = catalogRepository.insert(new FileEntry(
                null, firstSource.id(), "already-missing.dat", "already-missing.dat", knownContent.id(),
                "MISSING", 77, 222L, 333, 9, 12, 22, firstScanSource.id(), 98L));
        BasicFileAttributes reappearedAttributes = Files.readAttributes(reappearedFile, BasicFileAttributes.class);
        catalogRepository.insert(new FileEntry(
                null, firstSource.id(), "reappeared.dat", "reappeared.dat", knownContent.id(), "MISSING",
                reappearedAttributes.size(),
                reappearedAttributes.lastModifiedTime().toInstant().getEpochSecond(),
                reappearedAttributes.lastModifiedTime().toInstant().getNano(),
                5, 13, 23, firstScanSource.id(), 97L));
        FileEntry emptySourceEntry = catalogRepository.insert(new FileEntry(
                null, secondSource.id(), "old.dat", "old.dat", null, "PRESENT", 99,
                null, null, 2, 14, 24, null, null));
        FileEntry outsideEntry = catalogRepository.insert(new FileEntry(
                null, outsideSource.id(), "outside.dat", "outside.dat", knownContent.id(), "PRESENT", 77,
                444L, 555, 6, 15, 25, null, null));

        mockMvc.perform(post(discoveryPath(scanRun.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressCompleted").value(3));

        ScanRun afterDiscovery = scanRepository.findScanRunById(scanRun.scanRun().id()).orElseThrow();
        Job afterDiscoveryJob = jobRepository.findJobById(initialJob.id()).orElseThrow();
        JobStage pendingReconciliation = jobRepository.findJobStageByJobIdAndType(
                initialJob.id(), "RECONCILIATION").orElseThrow();
        assertEquals("PENDING", pendingReconciliation.status());
        assertNull(pendingReconciliation.resultJson());
        assertEquals(0, pendingReconciliation.attemptCount());
        assertNull(pendingReconciliation.startedAtMs());
        assertNull(pendingReconciliation.finishedAtMs());
        FileEntry staleBeforeReconciliation = requireFile(firstSource, stale.pathKey());
        FileEntry nullBeforeReconciliation = requireFile(firstSource, nullLastSeen.pathKey());
        FileEntry missingBeforeReconciliation = requireFile(firstSource, alreadyMissing.pathKey());
        FileEntry outsideBeforeReconciliation = requireFile(outsideSource, outsideEntry.pathKey());
        long contentCountBefore = countRows("content_record");

        jdbcTemplate.update("UPDATE source SET location_revision = location_revision + 1 WHERE id = ?",
                firstSource.id());
        Files.delete(currentFile);
        Files.delete(extraFile);
        Files.delete(reappearedFile);
        Files.delete(firstRoot);
        Files.delete(secondRoot);

        mockMvc.perform(post(reconciliationPath(scanRun.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStageType").doesNotExist())
                .andExpect(jsonPath("$.progressCompleted").value(2))
                .andExpect(jsonPath("$.progressTotal").value(2))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.stages[0].stageType").value("DISCOVERY"))
                .andExpect(jsonPath("$.stages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.stages[1].stageType").value("RECONCILIATION"))
                .andExpect(jsonPath("$.stages[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.stages[1].progressCompleted").value(2))
                .andExpect(jsonPath("$.stages[1].progressTotal").value(2))
                .andExpect(jsonPath("$.stages[1].attemptCount").value(1));

        ScanRun completedScanRun = scanRepository.findScanRunById(scanRun.scanRun().id()).orElseThrow();
        assertEquals("COMPLETED", completedScanRun.status());
        assertEquals(afterDiscovery.startedAtMs(), completedScanRun.startedAtMs());
        assertNotNull(completedScanRun.finishedAtMs());
        assertNull(completedScanRun.errorMessage());

        List<ScanRunSource> completedSources = scanRepository.findScanRunSourcesByScanRunId(scanRun.scanRun().id());
        assertEquals(2, completedSources.size());
        for (ScanRunSource completedSource : completedSources) {
            assertEquals("COMPLETED", completedSource.status());
            assertEquals(completedSource.traversalGeneration(), completedSource.completedGeneration());
            assertNotNull(completedSource.completedAtMs());
            assertNull(completedSource.errorMessage());
        }

        Job completedJob = jobRepository.findJobById(initialJob.id()).orElseThrow();
        assertEquals(1, completedJob.executionVersion());
        assertEquals("COMPLETED", completedJob.status());
        assertNull(completedJob.currentStageType());
        assertEquals(2, completedJob.progressCompleted());
        assertEquals(2L, completedJob.progressTotal());
        assertEquals(afterDiscoveryJob.attemptCount(), completedJob.attemptCount());
        assertEquals(afterDiscoveryJob.startedAtMs(), completedJob.startedAtMs());
        assertNotNull(completedJob.finishedAtMs());
        assertNull(completedJob.errorMessage());

        List<JobStage> stages = jobRepository.findJobStagesByJobId(initialJob.id());
        assertEquals(2, stages.size());
        assertTrue(stages.stream().allMatch(stage -> stage.resultJson() == null));
        JobStage reconciliation = stages.get(1);
        assertEquals("COMPLETED", reconciliation.status());
        assertEquals(1, reconciliation.attemptCount());
        assertNotNull(reconciliation.startedAtMs());
        assertNotNull(reconciliation.finishedAtMs());
        assertNull(reconciliation.errorMessage());

        assertEquals(withPresence(staleBeforeReconciliation, "MISSING"), requireFile(firstSource, stale.pathKey()));
        assertEquals(withPresence(nullBeforeReconciliation, "MISSING"),
                requireFile(firstSource, nullLastSeen.pathKey()));
        assertEquals(missingBeforeReconciliation, requireFile(firstSource, alreadyMissing.pathKey()));
        assertEquals(withPresence(emptySourceEntry, "MISSING"), requireFile(secondSource, emptySourceEntry.pathKey()));
        assertEquals(outsideBeforeReconciliation, requireFile(outsideSource, outsideEntry.pathKey()));

        FileEntry current = requireFile(firstSource, "current.dat");
        assertEquals("PRESENT", current.presenceStatus());
        assertEquals(firstScanSource.id(), current.lastSeenScanRunSourceId());
        assertEquals(1L, current.lastSeenTraversalGeneration());
        assertEquals("PRESENT", requireFile(firstSource, "extra.dat").presenceStatus());
        FileEntry reappeared = requireFile(firstSource, "reappeared.dat");
        assertEquals("PRESENT", reappeared.presenceStatus());
        assertEquals(6, reappeared.observationRevision());
        assertNull(reappeared.currentContentId());
        assertEquals(firstScanSource.id(), reappeared.lastSeenScanRunSourceId());
        assertEquals(1L, reappeared.lastSeenTraversalGeneration());

        assertEquals(contentCountBefore, countRows("content_record"));
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
        assertEquals(2, countRows("job_stage"));

        DurableState completedState = durableState();
        mockMvc.perform(post(reconciliationPath(scanRun.scanRun().id())))
                .andExpect(status().isConflict());
        assertEquals(completedState, durableState());
    }

    @Test
    void emptyDiscoveredSourceMarksAllPreviouslyPresentEntriesMissing() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("empty"));
        Source source = insertSource("Empty", root);
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        scanExecutionService.create(scanRun.scanRun().id());
        catalogRepository.insert(new FileEntry(
                null, source.id(), "first.dat", "first.dat", null, "PRESENT", 1,
                null, null, 0, 1, 1, null, null));
        catalogRepository.insert(new FileEntry(
                null, source.id(), "second.dat", "second.dat", null, "PRESENT", 2,
                null, null, 0, 1, 1, null, null));

        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        Files.delete(root);

        mockMvc.perform(post(reconciliationPath(scanRun.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressCompleted").value(1))
                .andExpect(jsonPath("$.progressTotal").value(1));

        assertEquals("MISSING", requireFile(source, "first.dat").presenceStatus());
        assertEquals("MISSING", requireFile(source, "second.dat").presenceStatus());
        ScanRunSource completedSource = scanRepository.findScanRunSourcesByScanRunId(scanRun.scanRun().id())
                .getFirst();
        assertEquals("COMPLETED", completedSource.status());
        assertEquals(completedSource.traversalGeneration(), completedSource.completedGeneration());
    }

    @Test
    void unknownScanRunAndMissingExecutionReturnNotFound() throws Exception {
        mockMvc.perform(post(reconciliationPath(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());

        Source source = insertSource("No execution", temporaryDirectory.resolve("unavailable"));
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        mockMvc.perform(post(reconciliationPath(scanRun.scanRun().id())))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "rejects ineligible state: {0}")
    @MethodSource("ineligibleMutations")
    void rejectsIneligibleStateWithoutMutation(String description, Consumer<EligibleExecution> mutation)
            throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("ineligible"));
        EligibleExecution execution = createEligibleExecution(root);
        mutation.accept(execution);
        DurableState beforeRequest = durableState();

        mockMvc.perform(post(reconciliationPath(execution.scanRunId())))
                .andExpect(status().isConflict());

        assertEquals(beforeRequest, durableState());
    }

    static Stream<Object[]> ineligibleMutations() {
        return Stream.of(
                mutation("ScanRun is not RUNNING", execution -> execution.update(
                        "UPDATE scan_run SET status = 'PENDING' WHERE id = ?", execution.scanRunId())),
                mutation("Job is not RUNNING", execution -> execution.update(
                        "UPDATE job SET status = 'PENDING' WHERE id = ?", execution.jobId())),
                mutation("Job current stage is not RECONCILIATION", execution -> execution.update(
                        "UPDATE job SET current_stage_type = 'DISCOVERY' WHERE id = ?", execution.jobId())),
                mutation("DISCOVERY stage does not exist", execution -> execution.update(
                        "DELETE FROM job_stage WHERE id = ?", execution.discoveryStageId())),
                mutation("DISCOVERY is not COMPLETED", execution -> execution.update(
                        "UPDATE job_stage SET status = 'RUNNING' WHERE id = ?", execution.discoveryStageId())),
                mutation("RECONCILIATION stage does not exist", execution -> execution.update(
                        "DELETE FROM job_stage WHERE id = ?", execution.reconciliationStageId())),
                mutation("RECONCILIATION is not PENDING", execution -> execution.update(
                        "UPDATE job_stage SET status = 'COMPLETED' WHERE id = ?",
                        execution.reconciliationStageId())),
                mutation("Source is not DISCOVERED", execution -> execution.update(
                        "UPDATE scan_run_source SET status = 'DISCOVERING' WHERE id = ?",
                        execution.scanRunSourceId())),
                mutation("traversal generation is not positive", execution -> execution.update(
                        "UPDATE scan_run_source SET traversal_generation = 0 WHERE id = ?",
                        execution.scanRunSourceId())),
                mutation("completed generation is populated", execution -> execution.update(
                        "UPDATE scan_run_source SET completed_generation = traversal_generation WHERE id = ?",
                        execution.scanRunSourceId())),
                mutation("Source completion timestamp is populated", execution -> execution.update(
                        "UPDATE scan_run_source SET completed_at_ms = 123 WHERE id = ?",
                        execution.scanRunSourceId())));
    }

    @Test
    void sourceSweepCompletionAndProgressRollbackTogether() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("rollback"));
        Files.writeString(root.resolve("first.dat"), "first");
        Files.writeString(root.resolve("second.dat"), "second");
        Source source = insertSource("Rollback", root);
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();
        FileEntry unseen = catalogRepository.insert(new FileEntry(
                null, source.id(), "unseen.dat", "unseen.dat", null, "PRESENT", 1,
                null, null, 0, 1, 1, null, null));
        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        assertEquals(2, jobRepository.findJobById(job.id()).orElseThrow().progressCompleted());
        ScanRunSource discoveredSource = scanRepository.findScanRunSourcesByScanRunId(scanRun.scanRun().id())
                .getFirst();
        JobStage reconciliation = jobRepository.findJobStageByJobIdAndType(job.id(), "RECONCILIATION")
                .orElseThrow();
        reconciliationExecutionState.start(job, reconciliation, 1, System.currentTimeMillis());

        Job startedJob = jobRepository.findJobById(job.id()).orElseThrow();
        assertEquals("RUNNING", startedJob.status());
        assertEquals("RECONCILIATION", startedJob.currentStageType());
        assertEquals(0, startedJob.progressCompleted());
        assertEquals(1L, startedJob.progressTotal());
        assertEquals(1, startedJob.attemptCount());
        JobStage startedStage = jobRepository.findJobStageById(reconciliation.id()).orElseThrow();
        assertEquals("RUNNING", startedStage.status());
        assertEquals(0, startedStage.progressCompleted());
        assertEquals(1L, startedStage.progressTotal());
        assertEquals(1, startedStage.attemptCount());
        assertNotNull(startedStage.startedAtMs());
        assertNull(startedStage.finishedAtMs());
        assertNull(startedStage.errorMessage());

        assertThrows(IllegalStateException.class, () -> reconciliationWriter.reconcile(
                Long.MAX_VALUE, reconciliation.id(), discoveredSource, 1, System.currentTimeMillis()));

        assertEquals("PRESENT", requireFile(source, unseen.pathKey()).presenceStatus());
        ScanRunSource rolledBackSource = scanRepository.findScanRunSourceById(discoveredSource.id()).orElseThrow();
        assertEquals("DISCOVERED", rolledBackSource.status());
        assertNull(rolledBackSource.completedGeneration());
        assertNull(rolledBackSource.completedAtMs());
        assertEquals(0, jobRepository.findJobById(job.id()).orElseThrow().progressCompleted());
        assertEquals(0, jobRepository.findJobStageById(reconciliation.id()).orElseThrow().progressCompleted());

        assertNotNull(ReconciliationWriter.class
                .getMethod("reconcile", long.class, long.class, ScanRunSource.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(ReconciliationExecutionState.class
                .getMethod("start", Job.class, JobStage.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(ReconciliationExecutionState.class
                .getMethod("complete", long.class, Job.class, JobStage.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        assertNull(ReconciliationService.class
                .getMethod("execute", long.class)
                .getAnnotation(Transactional.class));
        assertFalse(Arrays.stream(ReconciliationService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(DiscoveryFileWalker.class)));
    }

    private EligibleExecution createEligibleExecution(Path root) {
        Source source = insertSource("Eligible", root);
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();
        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        return new EligibleExecution(
                jdbcTemplate,
                scanRun.scanRun().id(),
                job.id(),
                scanRun.sources().getFirst().id(),
                stages.get(0).id(),
                stages.get(1).id());
    }

    private Source insertSource(String name, Path root) {
        String rootPath = root.toAbsolutePath().toString();
        return catalogRepository.insert(new Source(null, name, rootPath, rootPath, 0, 1, 1));
    }

    private ScanRunSource sourceFor(ScanRunDetails scanRun, Source source) {
        return scanRun.sources().stream()
                .filter(candidate -> candidate.sourceId() == source.id())
                .findFirst()
                .orElseThrow();
    }

    private FileEntry requireFile(Source source, String pathKey) {
        return catalogRepository.findFileEntryBySourceIdAndPathKey(source.id(), pathKey).orElseThrow();
    }

    private FileEntry withPresence(FileEntry entry, String presenceStatus) {
        return new FileEntry(
                entry.id(), entry.sourceId(), entry.relativePath(), entry.pathKey(), entry.currentContentId(),
                presenceStatus, entry.sizeBytes(), entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano(),
                entry.observationRevision(), entry.firstSeenAtMs(), entry.lastSeenAtMs(),
                entry.lastSeenScanRunSourceId(), entry.lastSeenTraversalGeneration());
    }

    private DurableState durableState() {
        return new DurableState(
                jdbcTemplate.queryForList("SELECT * FROM scan_run ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM scan_run_source ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM job ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM job_stage ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM file_entry ORDER BY id"));
    }

    private long countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    private String discoveryPath(long scanRunId) {
        return "/api/scan-runs/" + scanRunId + "/execution/discovery";
    }

    private String reconciliationPath(long scanRunId) {
        return "/api/scan-runs/" + scanRunId + "/execution/reconciliation";
    }

    private static Object[] mutation(String description, Consumer<EligibleExecution> mutation) {
        return new Object[] { description, mutation };
    }

    private record DurableState(
            List<Map<String, Object>> scanRuns,
            List<Map<String, Object>> scanRunSources,
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> stages,
            List<Map<String, Object>> fileEntries) {
    }

    private record EligibleExecution(
            JdbcTemplate jdbcTemplate,
            long scanRunId,
            long jobId,
            long scanRunSourceId,
            long discoveryStageId,
            long reconciliationStageId) {

        void update(String sql, long id) {
            jdbcTemplate.update(sql, id);
        }
    }
}
