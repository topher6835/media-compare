package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import io.github.topher6835.mediacompare.config.CatalogOwnership;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.job.*;
import io.github.topher6835.mediacompare.scan.*;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(Version2BackgroundIndexingTests.Hooks.class)
class Version2BackgroundIndexingTests {
    @TempDir Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired ScanRunService requests;
    @Autowired JobRepository jobs;
    @Autowired ScanRepository scans;
    @Autowired Version2BackgroundIndexingService background;
    @Autowired RecordingExecutor executor;
    @Autowired BlockingWalker walker;
    @Autowired InterruptibleHasher hasher;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void clear() throws Exception {
        drain();
        for (String table : List.of("content_hash", "job_stage", "file_entry", "scan_run_source",
                "working_set_content", "analysis_record", "job", "scan_run", "working_set", "content_record", "source")) {
            jdbc.update("DELETE FROM " + table);
        }
        walker.entered = new CountDownLatch(1);
        walker.release = new CountDownLatch(0);
        walker.interruptAfterBatch = false;
        walker.observedTransaction = false;
        walker.committedJobSeen = false;
        hasher.calls = 0;
        hasher.interruptAt = 0;
        hasher.observedTransaction = false;
    }

    @Test
    void handoffReturnsBeforeCompletionAndWorkerSeesCommittedRowsOutsideTransaction() throws Exception {
        long scanId = request("success");
        walker.release = new CountDownLatch(1);
        ScanExecutionDetails accepted;
        try {
            accepted = background.start(scanId);
            assertTrue(walker.entered.await(10, TimeUnit.SECONDS));
            assertEquals("PENDING", accepted.job().status());
            assertEquals("RUNNING", jobs.findJobById(accepted.job().id()).orElseThrow().status());
            assertTrue(walker.committedJobSeen);
            assertFalse(walker.observedTransaction);
            assertThrows(Version2ExecutionConflictException.class, () -> background.start(request("blocked")));
        } finally {
            walker.release.countDown();
            drain();
        }
        assertEquals("COMPLETED", jobs.findJobById(accepted.job().id()).orElseThrow().status());
        assertEquals("COMPLETED", scans.findScanRunById(scanId).orElseThrow().status());
        assertFalse(hasher.observedTransaction);
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Long.class));
    }

    @Test
    void ordinaryDiscoveryFailureRemainsInspectable() throws Exception {
        long scanId = request("missing");
        ScanRunSource source = scans.findScanRunSourcesByScanRunId(scanId).getFirst();
        jdbc.update("UPDATE source SET root_path = ? WHERE id = ?", directory.resolve("absent").toString(), source.sourceId());
        Job accepted = background.start(scanId).job();
        drain();
        assertFailed(accepted, "DISCOVERY");
        assertEquals("Discovery failed for Source " + source.sourceId(), jobs.findJobById(accepted.id()).orElseThrow().errorMessage());
    }

    @Test
    void unexpectedPreflightExceptionFinalizesTheStillPendingExecution() throws Exception {
        long scanId = request("stale");
        jdbc.update("UPDATE source SET location_revision = location_revision + 1");
        Job job = background.start(scanId).job();
        drain();
        assertEquals("FAILED", jobs.findJobById(job.id()).orElseThrow().status());
        assertEquals("Execution failed unexpectedly", jobs.findJobById(job.id()).orElseThrow().errorMessage());
        assertEquals("PENDING", jobs.findJobStagesByJobId(job.id()).getFirst().status());
        assertEquals("FAILED", scans.findScanRunById(scanId).orElseThrow().status());
    }

    @Test
    void rejectedSubmissionFailsDurableRecordsWithoutCallerRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);
        executor.execute(() -> { entered.countDown(); await(release); });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        executor.execute(queuedDone::countDown);
        long scanId = request("rejected");
        try {
            assertThrows(Version2SchedulingException.class, () -> background.start(scanId));
            Job job = jobs.findJobByScanRunIdAndTypeAndExecutionVersion(scanId, "SCAN", 2).orElseThrow();
            assertEquals("FAILED", job.status());
            assertEquals("Execution could not be scheduled", job.errorMessage());
            assertNull(job.currentStageType());
            assertEquals("FAILED", scans.findScanRunById(scanId).orElseThrow().status());
            assertEquals("PENDING", jobs.findJobStagesByJobId(job.id()).getFirst().status());
            assertTrue(jobs.findActiveVersion2ScanJobs().isEmpty());
            assertEquals(1, walker.entered.getCount(), "discovery must never run on caller");
        } finally {
            release.countDown();
            assertTrue(queuedDone.await(10, TimeUnit.SECONDS));
            drain();
        }
    }

    @Test
    void ambientTransactionIsRejectedBeforeCreation() throws Exception {
        long scanId = request("transaction");
        assertThrows(IllegalTransactionStateException.class,
                () -> new TransactionTemplate(transactions).execute(status -> background.start(scanId)));
        assertTrue(jobs.findJobByScanRunIdAndTypeAndExecutionVersion(scanId, "SCAN", 2).isEmpty());
    }

    @Test
    void interruptedHashReadStopsInsteadOfBecomingAFailedCandidate() throws Exception {
        long scanId = request("hash-interrupt");
        Files.writeString(directory.resolve("hash-interrupt/two.txt"), "more");
        hasher.interruptAt = 2;
        Job job = background.start(scanId).job();
        drain();
        assertFailed(job, "CONTENT_HASHING");
        assertEquals(2, hasher.calls);
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Long.class));
        assertNull(jobs.findJobStageByJobIdAndType(job.id(), "CONTENT_HASHING").orElseThrow().resultJson());
        assertNotNull(jobs.findJobStageByJobIdAndType(job.id(), "CONTENT_ASSIGNMENT").orElseThrow().resultJson());
        assertTrue(jobs.findActiveVersion2ScanJobs().isEmpty());
    }

    @Test
    void interruptedDiscoveryRetainsItsCommittedBatchAndNeverReconciles() throws Exception {
        walker.interruptAfterBatch = true;
        Job job = background.start(request("discovery-interrupt")).job();
        drain();
        assertFailed(job, "DISCOVERY");
        assertEquals(250L, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry", Long.class));
        assertEquals(1, jobs.findJobStagesByJobId(job.id()).size());
        assertEquals("FAILED", scans.findScanRunSourcesByScanRunId(job.scanRunId()).getFirst().status());
    }

    private long request(String name) throws Exception {
        Path root = Files.createDirectory(directory.resolve(name));
        Files.writeString(root.resolve("one.txt"), "content");
        Source source = catalog.insert(new Source(null, name, root.toString(), root.toString(), 0, 1, 1));
        return requests.create(List.of(source.id())).scanRun().id();
    }

    private void assertFailed(Job accepted, String stage) {
        Job job = jobs.findJobById(accepted.id()).orElseThrow();
        assertEquals("FAILED", job.status());
        assertNull(job.currentStageType());
        assertEquals("FAILED", scans.findScanRunById(job.scanRunId()).orElseThrow().status());
        assertEquals("FAILED", jobs.findJobStageByJobIdAndType(job.id(), stage).orElseThrow().status());
    }

    private void drain() throws Exception {
        assertTrue(executor.lastDone.await(10, TimeUnit.SECONDS), "indexing worker did not drain");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IndexingInterruptedException.check();
        }
    }

    @TestConfiguration
    static class Hooks {
        @Bean @Primary BlockingWalker walker(JdbcTemplate jdbc) { return new BlockingWalker(jdbc); }
        @Bean @Primary InterruptibleHasher hasher() { return new InterruptibleHasher(); }
        @Bean @Primary RecordingExecutor recordingExecutor(CatalogOwnership ownership, Version2IndexingStartup startup) {
            return new RecordingExecutor(ownership, startup);
        }
    }

    static class RecordingExecutor extends Version2IndexingExecutor {
        volatile CountDownLatch lastDone = new CountDownLatch(0);
        RecordingExecutor(CatalogOwnership ownership, Version2IndexingStartup startup) { super(ownership, startup); }
        @Override
        public void execute(Runnable task) {
            CountDownLatch done = new CountDownLatch(1);
            lastDone = done;
            try {
                super.execute(() -> { try { task.run(); } finally { done.countDown(); } });
            } catch (RuntimeException exception) {
                done.countDown();
                throw exception;
            }
        }
    }

    static class BlockingWalker extends DiscoveryFileWalker {
        final JdbcTemplate jdbc;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile boolean observedTransaction;
        volatile boolean committedJobSeen;
        volatile boolean interruptAfterBatch;
        BlockingWalker(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        public void walk(Path root, Consumer<DiscoveredFile> observer) throws IOException {
            observedTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            committedJobSeen = jdbc.queryForObject("SELECT COUNT(*) FROM job WHERE execution_version = 2 AND status = 'RUNNING'", Long.class) == 1;
            entered.countDown();
            await(release);
            if (interruptAfterBatch) {
                for (int index = 0; index < 251; index++) {
                    if (index == 250) Thread.currentThread().interrupt();
                    observer.accept(new DiscoveredFile("file-" + index + ".txt", 1, 1, 0));
                }
                return;
            }
            super.walk(root, observer);
        }
    }

    static class InterruptibleHasher extends ContentHashFileHasher {
        volatile int calls;
        volatile int interruptAt;
        volatile boolean observedTransaction;
        @Override
        public String hash(Path root, ContentHashCandidate candidate) throws IOException {
            observedTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            if (++calls == interruptAt) throw new InterruptedIOException("fixture interrupted read");
            return super.hash(root, candidate);
        }
    }
}
