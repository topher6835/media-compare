package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.analysis.ContentHashIntegrityException;
import io.github.topher6835.mediacompare.analysis.ContentHashingStageResult;
import io.github.topher6835.mediacompare.analysis.ContentHashingStageResultCodec;
import io.github.topher6835.mediacompare.analysis.StaleContentHashException;
import io.github.topher6835.mediacompare.analysis.Version2ContentHashingService;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ContentAssignmentStageResult;
import io.github.topher6835.mediacompare.scan.ContentAssignmentStageResultCodec;
import io.github.topher6835.mediacompare.scan.DiscoveryExecutionFailedException;
import io.github.topher6835.mediacompare.scan.DiscoveredFile;
import io.github.topher6835.mediacompare.scan.DiscoveryFileWalker;
import io.github.topher6835.mediacompare.scan.InvalidStageResultException;
import io.github.topher6835.mediacompare.scan.ReconciliationService;
import io.github.topher6835.mediacompare.scan.ScanExecutionDefinition;
import io.github.topher6835.mediacompare.scan.ScanExecutionDetails;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;
import io.github.topher6835.mediacompare.scan.Version2ContentAssignmentService;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import io.github.topher6835.mediacompare.scan.Version2ExecutionFailedException;
import io.github.topher6835.mediacompare.scan.Version2ExecutionState;
import io.github.topher6835.mediacompare.scan.Version2ScanExecutionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import(Version2ScanExecutionTests.ScriptedHasherConfiguration.class)
class Version2ScanExecutionTests {

    private static final String VALID_DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private ScanRepository scanRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ScanRunService scanRunService;

    @Autowired
    private ScanExecutionService version1ExecutionService;

    @Autowired
    private Version2ScanExecutionService version2ExecutionService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private Version2ContentAssignmentService assignmentService;

    @Autowired
    private Version2ContentHashingService hashingService;

    @Autowired
    private ContentAssignmentStageResultCodec assignmentResultCodec;

    @Autowired
    private ContentHashingStageResultCodec hashingResultCodec;

    @Autowired
    private ScriptedContentHashFileHasher fileHasher;

    @Autowired
    private TransactionRecordingDiscoveryFileWalker fileWalker;

    @Autowired
    private Version2ExecutionState executionState;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_reconciliation");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_content_assignment");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_assignment_finalization");
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
        fileHasher.reset();
        fileWalker.reset();
    }

    @Test
    void createsVersion2ExecutionAndEnforcesAdmissionConstraints() throws Exception {
        ScanRunDetails first = createScanRun(Files.createDirectory(temporaryDirectory.resolve("first")));
        ScanRunDetails second = createScanRun(Files.createDirectory(temporaryDirectory.resolve("second")));

        Job version1 = version1ExecutionService.create(first.scanRun().id()).job();
        ScanExecutionDetails version2 = version2ExecutionService.create(first.scanRun().id());

        assertEquals(1, version1.executionVersion());
        assertEquals(2, version2.job().executionVersion());
        assertEquals("PENDING", version2.job().status());
        assertEquals(ScanExecutionDefinition.DISCOVERY, version2.job().currentStageType());
        assertEquals(1, version2.stages().size());
        assertEquals("PENDING", version2.stages().getFirst().status());
        assertThrows(Version2ExecutionConflictException.class,
                () -> version2ExecutionService.create(first.scanRun().id()));
        assertThrows(Version2ExecutionConflictException.class,
                () -> version2ExecutionService.create(second.scanRun().id()));

        jdbcTemplate.update("UPDATE job SET status = 'COMPLETED', current_stage_type = NULL WHERE id = ?",
                version2.job().id());
        ScanExecutionDetails next = version2ExecutionService.create(second.scanRun().id());
        assertEquals(2, next.job().executionVersion());
    }

    @Test
    void versionAwareReadsAndMutationSelectTheIntendedJob() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("versions"));
        Files.writeString(root.resolve("one.txt"), "one");
        ScanRunDetails scanRun = createScanRun(root);
        Job version1 = version1ExecutionService.create(scanRun.scanRun().id()).job();
        Job version2 = version2ExecutionService.create(scanRun.scanRun().id()).job();

        assertEquals(version1.id(), version1ExecutionService.findByScanRunId(scanRun.scanRun().id())
                .orElseThrow().job().id());
        assertEquals(version2.id(), version2ExecutionService.findByScanRunId(scanRun.scanRun().id())
                .orElseThrow().job().id());

        version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id());

        assertEquals("PENDING", jobRepository.findJobById(version1.id()).orElseThrow().status());
        assertEquals("RUNNING", jobRepository.findJobById(version2.id()).orElseThrow().status());
    }

    @Test
    void completesTheFullLifecycleWithOneJobAndDurableTypedResults() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("success"));
        Files.writeString(root.resolve("one.txt"), "same");
        Files.writeString(root.resolve("two.txt"), "same");
        ScanRunDetails requested = createScanRun(root);
        Job originalJob = version2ExecutionService.create(requested.scanRun().id()).job();

        version1ExecutionService.executeVersion2Discovery(requested.scanRun().id());
        assertTransition(requested.scanRun().id(), originalJob.id(), "RUNNING",
                ScanExecutionDefinition.RECONCILIATION, List.of("COMPLETED", "PENDING"));

        reconciliationService.executeVersion2(requested.scanRun().id());
        assertTransition(requested.scanRun().id(), originalJob.id(), "RUNNING",
                ScanExecutionDefinition.CONTENT_ASSIGNMENT,
                List.of("COMPLETED", "COMPLETED", "PENDING"));
        ScanRunSource source = scanRepository.findScanRunSourcesByScanRunId(requested.scanRun().id()).getFirst();
        assertEquals("COMPLETED", source.status());
        assertNotNull(source.completedAtMs());

        ContentAssignmentStageResult assignment = assignmentService.execute(requested.scanRun().id());
        assertEquals(new ContentAssignmentStageResult(1, 2, 0), assignment);
        assertTransition(requested.scanRun().id(), originalJob.id(), "RUNNING",
                ScanExecutionDefinition.CONTENT_HASHING,
                List.of("COMPLETED", "COMPLETED", "COMPLETED", "PENDING"));

        ContentHashingStageResult hashing = hashingService.execute(requested.scanRun().id());
        assertEquals(new ContentHashingStageResult(1, 2, 0, 0, 0), hashing);

        Job completed = jobRepository.findJobById(originalJob.id()).orElseThrow();
        ScanRun completedRun = scanRepository.findScanRunById(requested.scanRun().id()).orElseThrow();
        List<JobStage> stages = jobRepository.findJobStagesByJobId(originalJob.id());
        assertEquals("COMPLETED", completed.status());
        assertNull(completed.currentStageType());
        assertEquals("COMPLETED", completedRun.status());
        assertEquals(List.of(
                ScanExecutionDefinition.DISCOVERY,
                ScanExecutionDefinition.RECONCILIATION,
                ScanExecutionDefinition.CONTENT_ASSIGNMENT,
                ScanExecutionDefinition.CONTENT_HASHING), stages.stream().map(JobStage::stageType).toList());
        assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED"),
                stages.stream().map(JobStage::status).toList());
        assertEquals(assignment, assignmentResultCodec.read(stages.get(2).resultJson()));
        assertEquals(hashing, hashingResultCodec.read(stages.get(3).resultJson()));
        assertEquals("{\"version\":1,\"assignedCount\":2,\"skippedCount\":0}", stages.get(2).resultJson());
        assertEquals("{\"version\":1,\"hashedCount\":2,\"cachedCount\":0,\"skippedCount\":0,\"failedCount\":0}",
                stages.get(3).resultJson());
        assertFalse(fileHasher.observedTransaction());
        assertFalse(fileWalker.observedTransaction());
    }

    @Test
    void synchronousCoordinatorRunsAllStagesWithoutOwningOneLargeTransaction() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("coordinator"));
        Files.writeString(root.resolve("one.txt"), "one");
        ScanRunDetails scanRun = createScanRun(root);
        version2ExecutionService.create(scanRun.scanRun().id());

        ScanExecutionDetails result = version2ExecutionService.run(scanRun.scanRun().id());

        assertEquals("COMPLETED", result.job().status());
        assertEquals(4, result.stages().size());
        assertNull(Version2ScanExecutionService.class.getMethod("run", long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(Version2ExecutionState.class
                .getMethod("completeHashing", long.class, Job.class, JobStage.class,
                        String.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        assertFalse(fileHasher.observedTransaction());
        assertFalse(fileWalker.observedTransaction());
    }

    @Test
    void repeatedInvocationAndConditionalClaimDoNotRunAStageTwice() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("duplicate"));
        Files.writeString(root.resolve("one.txt"), "one");
        ScanRunDetails scanRun = createScanRun(root);
        Job job = version2ExecutionService.create(scanRun.scanRun().id()).job();

        version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id());
        long fileCount = countRows("file_entry");

        assertThrows(RuntimeException.class,
                () -> version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id()));
        assertEquals(fileCount, countRows("file_entry"));
        assertEquals(1, jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.DISCOVERY).orElseThrow().attemptCount());

        reconciliationService.executeVersion2(scanRun.scanRun().id());
        Job currentJob = jobRepository.findJobById(job.id()).orElseThrow();
        JobStage assignment = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.CONTENT_ASSIGNMENT).orElseThrow();
        executionState.startStage(currentJob, assignment, System.currentTimeMillis());
        assertThrows(IllegalStateException.class,
                () -> executionState.startStage(currentJob, assignment, System.currentTimeMillis()));
        assertEquals(1, jobRepository.findJobStageById(assignment.id()).orElseThrow().attemptCount());
        assertEquals(0, countRows("content_record"));
    }

    @Test
    void resultAndStageAdvancementRollbackTogether() throws Exception {
        PreparedV2 prepared = prepareThroughReconciliation("atomic-result", "one.txt");
        Job job = jobRepository.findJobById(prepared.jobId()).orElseThrow();
        JobStage assignment = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.CONTENT_ASSIGNMENT).orElseThrow();
        executionState.startStage(job, assignment, System.currentTimeMillis());
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_assignment_finalization
                BEFORE UPDATE OF current_stage_type ON job
                WHEN NEW.current_stage_type = 'CONTENT_HASHING'
                BEGIN
                    SELECT RAISE(ABORT, 'forced finalization failure');
                END
                """);

        assertThrows(RuntimeException.class, () -> executionState.completeAssignment(
                job, assignment, "{\"version\":1,\"assignedCount\":1,\"skippedCount\":0}",
                1, System.currentTimeMillis()));

        JobStage unchanged = jobRepository.findJobStageById(assignment.id()).orElseThrow();
        assertEquals("RUNNING", unchanged.status());
        assertNull(unchanged.resultJson());
        assertTrue(jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.CONTENT_HASHING).isEmpty());
        assertEquals(ScanExecutionDefinition.CONTENT_ASSIGNMENT,
                jobRepository.findJobById(job.id()).orElseThrow().currentStageType());
    }

    @Test
    void discoveryFailureFinalizesOnlyTheCurrentStage() {
        ScanRunDetails scanRun = createScanRun(temporaryDirectory.resolve("missing-root"));
        Job job = version2ExecutionService.create(scanRun.scanRun().id()).job();

        assertThrows(DiscoveryExecutionFailedException.class,
                () -> version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id()));

        assertTerminalFailure(scanRun.scanRun().id(), job.id(), ScanExecutionDefinition.DISCOVERY);
        assertEquals(1, jobRepository.findJobStagesByJobId(job.id()).size());
    }

    @Test
    void reconciliationFailureDoesNotCreateAssignmentStage() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("reconciliation-failure"));
        Files.writeString(root.resolve("one.txt"), "one");
        ScanRunDetails scanRun = createScanRun(root);
        Job job = version2ExecutionService.create(scanRun.scanRun().id()).job();
        version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id());
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_reconciliation
                BEFORE UPDATE OF status ON scan_run_source
                WHEN NEW.status = 'COMPLETED'
                BEGIN
                    SELECT RAISE(ABORT, 'forced reconciliation failure');
                END
                """);

        assertThrows(Version2ExecutionFailedException.class,
                () -> reconciliationService.executeVersion2(scanRun.scanRun().id()));

        assertTerminalFailure(scanRun.scanRun().id(), job.id(), ScanExecutionDefinition.RECONCILIATION);
        assertTrue(jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.CONTENT_ASSIGNMENT).isEmpty());
    }

    @Test
    void assignmentFailureDoesNotCreateHashingStage() throws Exception {
        PreparedV2 prepared = prepareThroughReconciliation("assignment-failure", "one.txt");
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_content_assignment
                BEFORE INSERT ON content_record
                BEGIN
                    SELECT RAISE(ABORT, 'forced assignment failure');
                END
                """);

        assertThrows(Version2ExecutionFailedException.class,
                () -> assignmentService.execute(prepared.scanRunId()));

        assertTerminalFailure(prepared.scanRunId(), prepared.jobId(),
                ScanExecutionDefinition.CONTENT_ASSIGNMENT);
        assertNull(jobRepository.findJobStageByJobIdAndType(
                prepared.jobId(), ScanExecutionDefinition.CONTENT_ASSIGNMENT).orElseThrow().resultJson());
        assertTrue(jobRepository.findJobStageByJobIdAndType(
                prepared.jobId(), ScanExecutionDefinition.CONTENT_HASHING).isEmpty());
    }

    @Test
    void hashingCandidateIssuesRemainACompletedExecution() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("hashing-issues"));
        Files.writeString(root.resolve("skip.txt"), "skip");
        Files.writeString(root.resolve("fail.txt"), "fail");
        ScanRunDetails scanRun = createScanRun(root);
        Job job = version2ExecutionService.create(scanRun.scanRun().id()).job();
        version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id());
        reconciliationService.executeVersion2(scanRun.scanRun().id());
        assignmentService.execute(scanRun.scanRun().id());

        ContentHashingStageResult result = hashingService.execute(scanRun.scanRun().id());

        assertEquals(new ContentHashingStageResult(1, 0, 0, 1, 1), result);
        assertEquals("COMPLETED", jobRepository.findJobById(job.id()).orElseThrow().status());
        assertEquals("COMPLETED", scanRepository.findScanRunById(scanRun.scanRun().id()).orElseThrow().status());
        JobStage stage = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.CONTENT_HASHING).orElseThrow();
        assertEquals("COMPLETED", stage.status());
        assertEquals(result, hashingResultCodec.read(stage.resultJson()));
    }

    @Test
    void wholeHashingFailureFinalizesTheLifecycle() throws Exception {
        PreparedV2 prepared = prepareThroughAssignment("hashing-failure", "explode.txt");

        Version2ExecutionFailedException failure = assertThrows(Version2ExecutionFailedException.class,
                () -> hashingService.execute(prepared.scanRunId()));

        assertInstanceOf(ContentHashIntegrityException.class, failure.getCause());
        assertTerminalFailure(prepared.scanRunId(), prepared.jobId(), ScanExecutionDefinition.CONTENT_HASHING);
        assertNull(jobRepository.findJobStageByJobIdAndType(
                prepared.jobId(), ScanExecutionDefinition.CONTENT_HASHING).orElseThrow().resultJson());
    }

    @Test
    void codecsRejectMalformedIncompatibleAndUnexpectedStoredResults() {
        assertThrows(InvalidStageResultException.class, () -> assignmentResultCodec.read("not-json"));
        assertThrows(InvalidStageResultException.class,
                () -> assignmentResultCodec.read("{\"version\":2,\"assignedCount\":1,\"skippedCount\":0}"));
        assertThrows(InvalidStageResultException.class,
                () -> hashingResultCodec.read("{\"version\":1,\"hashedCount\":1}"));
        assertThrows(InvalidStageResultException.class,
                () -> hashingResultCodec.read("{\"version\":1,\"hashedCount\":0,\"cachedCount\":0,"
                        + "\"skippedCount\":0,\"failedCount\":0,\"unexpected\":true}"));
    }

    private PreparedV2 prepareThroughReconciliation(String directoryName, String fileName) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        Files.writeString(root.resolve(fileName), "content");
        ScanRunDetails scanRun = createScanRun(root);
        Job job = version2ExecutionService.create(scanRun.scanRun().id()).job();
        version1ExecutionService.executeVersion2Discovery(scanRun.scanRun().id());
        reconciliationService.executeVersion2(scanRun.scanRun().id());
        return new PreparedV2(scanRun.scanRun().id(), job.id());
    }

    private PreparedV2 prepareThroughAssignment(String directoryName, String fileName) throws Exception {
        PreparedV2 prepared = prepareThroughReconciliation(directoryName, fileName);
        assignmentService.execute(prepared.scanRunId());
        return prepared;
    }

    private ScanRunDetails createScanRun(Path root) {
        String rootPath = root.toAbsolutePath().toString();
        Source source = catalogRepository.insert(new Source(
                null, "Source " + root.getFileName(), rootPath, rootPath, 0, 1, 1));
        return scanRunService.create(List.of(source.id()));
    }

    private void assertTransition(long scanRunId, long jobId, String scanStatus,
            String currentStage, List<String> stageStatuses) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId).orElseThrow();
        Job job = jobRepository.findJobById(jobId).orElseThrow();
        assertEquals(scanStatus, scanRun.status());
        assertEquals("RUNNING", job.status());
        assertEquals(currentStage, job.currentStageType());
        assertEquals(stageStatuses,
                jobRepository.findJobStagesByJobId(jobId).stream().map(JobStage::status).toList());
    }

    private void assertTerminalFailure(long scanRunId, long jobId, String failedStageType) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId).orElseThrow();
        Job job = jobRepository.findJobById(jobId).orElseThrow();
        JobStage stage = jobRepository.findJobStageByJobIdAndType(jobId, failedStageType).orElseThrow();
        assertEquals("FAILED", scanRun.status());
        assertEquals("FAILED", job.status());
        assertNull(job.currentStageType());
        assertEquals("FAILED", stage.status());
        assertNotNull(scanRun.finishedAtMs());
        assertNotNull(job.finishedAtMs());
        assertNotNull(stage.finishedAtMs());
        assertNotNull(scanRun.errorMessage());
        assertEquals(scanRun.errorMessage(), job.errorMessage());
        assertEquals(job.errorMessage(), stage.errorMessage());
        assertFalse(job.errorMessage().contains("forced"));
    }

    private long countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    private record PreparedV2(long scanRunId, long jobId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedHasherConfiguration {

        @Bean
        @Primary
        ScriptedContentHashFileHasher scriptedContentHashFileHasher() {
            return new ScriptedContentHashFileHasher();
        }

        @Bean
        @Primary
        TransactionRecordingDiscoveryFileWalker transactionRecordingDiscoveryFileWalker() {
            return new TransactionRecordingDiscoveryFileWalker();
        }
    }

    static class ScriptedContentHashFileHasher extends ContentHashFileHasher {

        private boolean observedTransaction;

        @Override
        public String hash(Path sourceRoot, ContentHashCandidate candidate) throws IOException {
            observedTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            return switch (candidate.relativePath()) {
                case "skip.txt" -> throw new StaleContentHashException(
                        candidate.fileEntryId(), "simulated stale evidence");
                case "fail.txt" -> throw new IOException("simulated candidate read failure");
                case "explode.txt" -> throw new ContentHashIntegrityException(
                        candidate.contentRecordId(), "simulated operation failure");
                default -> VALID_DIGEST;
            };
        }

        boolean observedTransaction() {
            return observedTransaction;
        }

        void reset() {
            observedTransaction = false;
        }
    }

    static class TransactionRecordingDiscoveryFileWalker extends DiscoveryFileWalker {

        private boolean observedTransaction;

        @Override
        public void walk(Path root, Consumer<DiscoveredFile> observer) throws IOException {
            observedTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            super.walk(root, observer);
        }

        boolean observedTransaction() {
            return observedTransaction;
        }

        void reset() {
            observedTransaction = false;
        }
    }
}
