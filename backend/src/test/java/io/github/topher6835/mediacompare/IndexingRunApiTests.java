package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.scan.*;
import io.github.topher6835.mediacompare.scan.V3TestHost;
import io.github.topher6835.mediacompare.web.IndexingRunResponse;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
@DirtiesContext
class IndexingRunApiTests {
    @TempDir static Path databaseDirectory;
    @TempDir Path directory;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("api.db") + "?foreign_keys=on");
    }
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired ScanRepository scans;
    @Autowired ScanRunService requests;
    @Autowired JobRepository jobs;
    @Autowired Version3ScanExecutionService executions;
    @Autowired ScanExecutionService legacy;
    @Autowired Version2InterruptionRecovery recovery;
    @Autowired IndexingRunService starts;
    @Autowired ControlledExecutor executor;
    @Autowired BarrierAcceptance acceptance;

    @BeforeEach
    void clear() {
        executor.reset();
        acceptance.setBarrier(null);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_acceptance");
        for (String table : List.of("content_hash", "job_stage", "source_membership", "file_entry",
                "scan_run_source", "working_set_content", "analysis_record", "job", "scan_run",
                "working_set", "content_record", "source", "location_context")) jdbc.update("DELETE FROM " + table);
    }

    @Test
    void acceptsCanonicalKeyAndCommitsWholeExecutionBeforeWorkerRuns() throws Exception {
        Source source = source("first");
        String key = UUID.randomUUID().toString();
        var result = start(key.toUpperCase(java.util.Locale.ROOT), List.of(source.id())).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.requestKey").value(key)).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stages.length()").value(1)).andExpect(jsonPath("$.stages[0].stageType").value("DISCOVERY"))
                .andExpect(jsonPath("$.stages[0].resultJson").doesNotExist()).andReturn();
        IndexingRunResponse response = body(result);
        assertEquals("/api/indexing-runs/" + response.scanRunId(), result.getResponse().getHeader("Location"));
        assertEquals(key, scans.findScanRunById(response.scanRunId()).orElseThrow().requestKey());
        assertEquals(1, count("job"));
        assertEquals(1, count("scan_run"));
        assertEquals(1, count("scan_run_source"));
        executor.runAccepted();
        assertTrue(executor.committedSeen);
        assertFalse(executor.submissionTransaction);
        assertFalse(executor.workerTransaction);
        assertEquals("COMPLETED", detail(response.scanRunId()).status());
    }

    @Test
    void replayIsOrderInsensitiveAndNeverResubmitsPendingOrCompletedExecution() throws Exception {
        Source one = source("one");
        Source two = source("two");
        String key = UUID.randomUUID().toString();
        IndexingRunResponse first = body(start(key, List.of(two.id(), one.id())).andExpect(status().isAccepted()).andReturn());
        IndexingRunResponse replay = body(start(key, List.of(one.id(), two.id())).andExpect(status().isOk()).andReturn());
        assertEquals(first, replay);
        executor.runAccepted();
        var completed = body(start(key, List.of(one.id(), two.id())).andExpect(status().isOk()).andReturn());
        assertEquals(first.scanRunId(), completed.scanRunId());
        assertEquals(first.jobId(), completed.jobId());
        assertEquals("COMPLETED", completed.status());
        assertFalse(completed.completedWithIssues());
        assertEquals(2, completed.assignmentResult().assignedCount());
        assertEquals(2, completed.hashingResult().hashedCount());
        assertEquals(1, executor.submissions.get());
        assertEquals(1, count("job"));
        assertTrue(jobs.findJobStagesByJobId(first.jobId()).stream().allMatch(stage -> stage.attemptCount() == 1));
    }

    @Test
    void sameKeyDifferentSourcesConflictsWithoutMutation() throws Exception {
        Source one = source("one");
        Source two = source("two");
        String key = UUID.randomUUID().toString();
        start(key, List.of(one.id())).andExpect(status().isAccepted());
        var before = jdbc.queryForList("SELECT * FROM scan_run");
        start(key, List.of(two.id())).andExpect(status().isConflict()).andExpect(content().string(""));
        assertEquals(before, jdbc.queryForList("SELECT * FROM scan_run"));
        assertEquals(1, executor.submissions.get());
    }

    @Test
    void globalAdmissionConflictRollsBackRequestKeySourcesAndScanRun() throws Exception {
        Source one = source("one");
        Source two = source("two");
        start(UUID.randomUUID().toString(), List.of(one.id())).andExpect(status().isAccepted());
        String rejected = UUID.randomUUID().toString();
        start(rejected, List.of(two.id())).andExpect(status().isConflict());
        assertTrue(scans.findScanRunIdByRequestKey(rejected).isEmpty());
        assertEquals(1, count("scan_run"));
        assertEquals(1, count("scan_run_source"));
        assertEquals(1, count("job_stage"));
        assertEquals(1, executor.submissions.get());
    }

    @Test
    void simultaneousSameKeyInsertRaceUsesUniqueConstraintAndSubmitsOnce() throws Exception {
        Source source = source("race");
        String key = UUID.randomUUID().toString();
        acceptance.setBarrier(new CyclicBarrier(2));
        try (var callers = Executors.newFixedThreadPool(2)) {
            Future<IndexingRunService.StartResult> one = callers.submit(() -> starts.start(key, List.of(source.id())));
            Future<IndexingRunService.StartResult> two = callers.submit(() -> starts.start(key, List.of(source.id())));
            var first = one.get(15, TimeUnit.SECONDS);
            var second = two.get(15, TimeUnit.SECONDS);
            assertNotEquals(first.created(), second.created());
            assertEquals(first.run().scanRun().id(), second.run().scanRun().id());
            assertEquals(first.run().job().id(), second.run().job().id());
        }
        assertEquals(2, acceptance.attemptCount());
        assertEquals(1, count("scan_run"));
        assertEquals(1, count("job"));
        assertEquals(1, executor.submissions.get());
    }

    @Test
    void unrelatedIntegrityFailureIsSafe500AndRollsBackAllAcceptance() throws Exception {
        Source source = source("failure");
        jdbc.execute("""
                CREATE TRIGGER fail_acceptance BEFORE INSERT ON job_stage
                BEGIN SELECT RAISE(ABORT, 'secret SQL fixture detail'); END
                """);
        start(UUID.randomUUID().toString(), List.of(source.id())).andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
        assertEquals(0, count("scan_run"));
        assertEquals(0, count("scan_run_source"));
        assertEquals(0, count("job"));
        assertEquals(0, executor.submissions.get());
    }

    @Test
    void concurrentKeyMisuseReturnsConflictAfterUniqueRaceWithoutAnOrphan() throws Exception {
        Source one = source("race-one");
        Source two = source("race-two");
        String key = UUID.randomUUID().toString();
        acceptance.setBarrier(new CyclicBarrier(2));
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> start(key, List.of(one.id())).andReturn().getResponse().getStatus());
            var second = callers.submit(() -> start(key, List.of(two.id())).andReturn().getResponse().getStatus());
            assertEquals(List.of(202, 409), java.util.stream.Stream.of(first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(2, acceptance.attemptCount());
        assertEquals(1, count("scan_run"));
        assertEquals(1, count("scan_run_source"));
        assertEquals(1, executor.submissions.get());
    }

    @Test
    void historicalV1AndV3CannotAcquireCompetingOwnership() throws Exception {
        long id = requests.create(List.of(source("ownership-race").id())).scanRun().id();
        assertThrows(ScanExecutionAlreadyExistsException.class, () -> legacy.create(id));
        executions.create(id);
        assertThrows(ScanExecutionAlreadyExistsException.class, () -> legacy.create(id));
        assertEquals(1, count("job"));
        assertEquals(1, count("job_stage"));
    }

    @Test
    void schedulerRejectionIs503AndSameKeyReplaysDurableFailure() throws Exception {
        Source source = source("rejection");
        String key = UUID.randomUUID().toString();
        executor.reject = true;
        start(key, List.of(source.id())).andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
        var failed = body(start(key, List.of(source.id())).andExpect(status().isOk()).andReturn());
        assertEquals("FAILED", failed.status());
        assertEquals("Execution could not be scheduled", failed.errorMessage());
        assertEquals("PENDING", failed.stages().getFirst().status());
        assertFalse(jobs.hasActiveScanJob());
        assertEquals(1, executor.submissions.get());
        executor.reject = false;
        var replacement = body(start(UUID.randomUUID().toString(), List.of(source.id())).andExpect(status().isAccepted()).andReturn());
        assertNotEquals(failed.scanRunId(), replacement.scanRunId());
    }

    @Test
    void rejectsMalformedRequestsAndUnknownSourcesWithoutRows() throws Exception {
        for (String body : List.of("", "null", "{}", "{\"requestKey\":\"bad\",\"sourceIds\":[1]}",
                "{\"requestKey\":\"1-1-1-1-1\",\"sourceIds\":[1]}")) {
            mvc.perform(post("/api/indexing-runs").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        String key = UUID.randomUUID().toString();
        for (String ids : List.of("null", "[]", "[0]", "[-1]", "[null]", "[1,1]")) {
            mvc.perform(post("/api/indexing-runs").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestKey\":\"" + key + "\",\"sourceIds\":" + ids + "}")).andExpect(status().isBadRequest());
        }
        start(key, java.util.stream.LongStream.rangeClosed(1, 1001).boxed().toList()).andExpect(status().isBadRequest());
        start(key, List.of(999L)).andExpect(status().isNotFound());
        assertEquals(0, count("scan_run"));
        assertEquals(0, executor.submissions.get());
    }

    @Test
    void normalCreationExcludesBothExecutionOwnershipDirections() throws Exception {
        Source source = source("ownership");
        long firstScan = requests.create(List.of(source.id())).scanRun().id();
        historicalV1(firstScan);
        assertThrows(Version2ExecutionConflictException.class, () -> executions.create(firstScan));
        long secondScan = requests.create(List.of(source.id())).scanRun().id();
        executions.create(secondScan);
        mvc.perform(post("/api/scan-runs/" + secondScan + "/execution")).andExpect(status().isConflict());
        assertEquals(2, count("job"));
    }

    @Test
    void unknownAndV1OnlyDetailsAre404() throws Exception {
        mvc.perform(get("/api/indexing-runs/999")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        long id = requests.create(List.of(source("v1").id())).scanRun().id();
        historicalV1(id);
        mvc.perform(get("/api/indexing-runs/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void discoveryFailureAndStartupInterruptionRemainReplayable() throws Exception {
        Source source = source("missing");
        String key = UUID.randomUUID().toString();
        var accepted = body(start(key, List.of(source.id())).andExpect(status().isAccepted()).andReturn());
        jdbc.update("UPDATE source SET root_path = ? WHERE id = ?", directory.resolve("absent").toString(), source.id());
        executor.runAccepted();
        var failed = body(start(key, List.of(source.id())).andExpect(status().isOk()).andReturn());
        assertEquals(accepted.jobId(), failed.jobId());
        assertEquals("FAILED", failed.status());
        assertEquals("Version-3 discovery failed for Source " + source.id(), failed.errorMessage());
        assertEquals("FAILED", failed.stages().getFirst().status());
        jdbc.update("UPDATE source SET root_path = ? WHERE id = ?", source.rootPath(), source.id());
        String nextKey = UUID.randomUUID().toString();
        var interrupted = body(start(nextKey, List.of(source.id())).andExpect(status().isAccepted()).andReturn());
        executor.held.clear(); // Models a process that exited before scheduling its accepted task.
        recovery.failIfActive(interrupted.jobId(), Version2InterruptionRecovery.RESTART_MESSAGE);
        var replay = body(start(nextKey, List.of(source.id())).andExpect(status().isOk()).andReturn());
        assertEquals("FAILED", replay.status());
        assertEquals(Version2InterruptionRecovery.RESTART_MESSAGE, replay.errorMessage());
        assertFalse(replay.completedWithIssues());
    }

    private Source source(String name) throws Exception {
        Path root = Files.createDirectory(directory.resolve(name));
        Files.writeString(root.resolve("one.txt"), "content");
        return V3TestHost.boundSource(catalog, jdbc, root, name);
    }
    private ResultActions start(String key, List<Long> ids) throws Exception {
        return mvc.perform(post("/api/indexing-runs").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requestKey", key, "sourceIds", ids))));
    }
    private IndexingRunResponse body(MvcResult result) { return json.readValue(result.getResponse().getContentAsByteArray(), IndexingRunResponse.class); }
    private IndexingRunResponse detail(long id) throws Exception {
        return body(mvc.perform(get("/api/indexing-runs/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn());
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void historicalV1(long scanRunId) {
        long now = System.currentTimeMillis();
        jobs.insert(new Job(null, scanRunId, "SCAN", 1, "COMPLETED", null,
                0, null, 1, now, now, now, null));
    }

    @TestConfiguration
    static class Hooks {
        @Bean @Primary Version3AuthorityCapture trustedCapture(
                CatalogRepository catalog, LocationContextRepository contexts) {
            return V3TestHost.trustedCapture(catalog, contexts);
        }
        @Bean @Primary Version3DiscoveryWalker trustedWalker() {
            return V3TestHost.trustedWalker();
        }
        @Bean @Primary ControlledExecutor controlledExecutor(CatalogOwnership ownership, Version2IndexingStartup startup, JdbcTemplate jdbc) {
            return new ControlledExecutor(ownership, startup, jdbc);
        }
        @Bean @Primary BarrierAcceptance barrierAcceptance(ScanRepository scans, ScanRunService requests, Version3ScanExecutionService executions) {
            return new BarrierAcceptance(scans, requests, executions);
        }
    }

    static class ControlledExecutor extends Version2IndexingExecutor {
        final ConcurrentLinkedQueue<Runnable> held = new ConcurrentLinkedQueue<>();
        final AtomicInteger submissions = new AtomicInteger();
        final JdbcTemplate jdbc;
        volatile boolean reject;
        volatile boolean committedSeen;
        volatile boolean workerTransaction;
        volatile boolean submissionTransaction;
        ControlledExecutor(CatalogOwnership ownership, Version2IndexingStartup startup, JdbcTemplate jdbc) {
            super(ownership, startup); this.jdbc = jdbc;
        }
        @Override public void execute(Runnable task) {
            submissionTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            submissions.incrementAndGet();
            if (reject) throw new RejectedExecutionException("fixture");
            held.add(task);
        }
        void runAccepted() throws Exception {
            Runnable task = held.remove();
            CompletableFuture<Void> finished = new CompletableFuture<>();
            super.execute(() -> {
                try {
                    workerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
                    committedSeen = jdbc.queryForObject("SELECT COUNT(*) FROM job j JOIN scan_run r ON r.id = j.scan_run_id JOIN job_stage s ON s.job_id = j.id WHERE j.status = 'PENDING' AND r.request_key IS NOT NULL", Long.class) == 1;
                    task.run();
                    finished.complete(null);
                } catch (Throwable failure) { finished.completeExceptionally(failure); }
            });
            finished.get(15, TimeUnit.SECONDS);
        }
        void reset() { held.clear(); submissions.set(0); reject = false; committedSeen = false; workerTransaction = false; submissionTransaction = false; }
    }

    static class BarrierAcceptance extends IndexingRunAcceptance {
        volatile CyclicBarrier barrier;
        final AtomicInteger attempts = new AtomicInteger();
        BarrierAcceptance(ScanRepository scans, ScanRunService requests, Version3ScanExecutionService executions) { super(scans, requests, executions); }
        public void setBarrier(CyclicBarrier barrier) { this.barrier = barrier; attempts.set(0); }
        public int attemptCount() { return attempts.get(); }
        @Override @Transactional
        public ScanExecutionDetails accept(String key, List<Long> sourceIds) {
            if (barrier != null) {
                attempts.incrementAndGet();
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            return super.accept(key, sourceIds);
        }
    }
}
