package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.analysis.StaleContentHashException;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.job.*;
import io.github.topher6835.mediacompare.scan.*;
import io.github.topher6835.mediacompare.web.IndexingRunResponse;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({IndexingRunApiTests.Hooks.class, IndexingRunReadApiTests.ReadHooks.class})
class IndexingRunReadApiTests {
    @TempDir Path directory;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired CountingJdbc jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired ScanRepository scans;
    @Autowired JobRepository jobs;
    @Autowired ScanExecutionService discovery;
    @Autowired ReconciliationService reconciliation;
    @Autowired ReconciliationExecutionState reconciliationState;
    @Autowired Version2ContentAssignmentService assignment;
    @Autowired Version2ExecutionState state;
    @Autowired Version2InterruptionRecovery recovery;
    @Autowired IndexingRunService starts;
    @Autowired IndexingRunApiTests.ControlledExecutor executor;

    @BeforeEach
    void clear() {
        executor.reset();
        jdbc.execute("DROP TRIGGER IF EXISTS fail_hashing");
        for (String table : List.of("content_hash", "job_stage", "source_membership", "file_entry", "scan_run_source", "working_set_content",
                "analysis_record", "job", "scan_run", "working_set", "content_record", "source", "location_context")) jdbc.update("DELETE FROM " + table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISCOVERY", "RECONCILIATION", "CONTENT_ASSIGNMENT", "CONTENT_HASHING"})
    void readsEachRunningStageAndReplayWithoutMutation(String type) throws Exception {
        var accepted = pending(List.of(source("running").id()));
        long scanId = accepted.scanRun().id();
        long jobId = accepted.job().id();
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE scan_run SET status = 'RUNNING', started_at_ms = ? WHERE id = ?", now, scanId);
        jdbc.update("UPDATE job SET status = 'RUNNING', current_stage_type = ?, started_at_ms = ?, attempt_count = 1 WHERE id = ?", type, now, jobId);
        if (!type.equals("DISCOVERY")) {
            jdbc.update("UPDATE job_stage SET status = 'COMPLETED', started_at_ms = ?, finished_at_ms = ?, attempt_count = 1 WHERE job_id = ?", now, now, jobId);
            jobs.insert(new JobStage(null, jobId, type, null, "RUNNING", 0, null, 1, now, now, null, null));
        } else {
            jdbc.update("UPDATE job_stage SET status = 'RUNNING', started_at_ms = ?, attempt_count = 1 WHERE job_id = ?", now, jobId);
        }
        jdbc.update("UPDATE job SET progress_completed = 7, progress_total = 12 WHERE id = ?", jobId);
        jdbc.update("UPDATE job_stage SET progress_completed = 7, progress_total = 12 WHERE job_id = ? AND stage_type = ?", jobId, type);
        var before = jdbc.queryForList("SELECT * FROM job_stage");
        var response = detail(scanId);
        assertEquals(jobId, response.jobId());
        assertEquals(accepted.sourceIds(), response.sourceIds());
        assertEquals("RUNNING", response.status());
        assertEquals(type, response.currentStage());
        assertEquals(7, response.progressCompleted());
        assertEquals(12L, response.progressTotal());
        assertNotNull(response.startedAtMs());
        assertNull(response.finishedAtMs());
        assertFalse(response.completedWithIssues());
        var replay = starts.start(accepted.scanRun().requestKey(), accepted.sourceIds());
        assertFalse(replay.created());
        assertEquals(jobId, replay.run().job().id());
        assertEquals(1, executor.submissions.get());
        assertEquals(before, jdbc.queryForList("SELECT * FROM job_stage"));
    }

    @Test
    void completedIssuesAreTypedDerivedAndNotPersistedAsAStatus() throws Exception {
        Source source = source("issues");
        Files.writeString(Path.of(source.rootPath()).resolve("skipped.txt"), "skip");
        Files.writeString(Path.of(source.rootPath()).resolve("failed.txt"), "fail");
        var accepted = pending(List.of(source.id()));
        executor.runAccepted();
        var response = detail(accepted.scanRun().id());
        assertEquals("COMPLETED", response.status());
        assertTrue(response.completedWithIssues());
        assertEquals(3, response.assignmentResult().assignedCount());
        assertEquals(1, response.hashingResult().hashedCount());
        assertEquals(1, response.hashingResult().skippedCount());
        assertEquals(1, response.hashingResult().failedCount());
        assertNotNull(response.finishedAtMs());
        mvc.perform(get("/api/indexing-runs/" + response.scanRunId()))
                .andExpect(jsonPath("$.hashingResult.version").doesNotExist());
        mvc.perform(get("/api/indexing-runs/source-status"))
                .andExpect(jsonPath("$.active").isEmpty())
                .andExpect(jsonPath("$.sources[0].latest.completedWithIssues").value(true));
    }

    @Test
    void laterStageFailureExposesSafeErrorAndEarlierResults() throws Exception {
        var accepted = pending(List.of(source("late-failure").id()));
        jdbc.execute("""
                CREATE TRIGGER fail_hashing BEFORE INSERT ON analysis_record
                BEGIN SELECT RAISE(ABORT, 'private database details'); END
                """);
        executor.runAccepted();
        var response = detail(accepted.scanRun().id());
        assertEquals("FAILED", response.status());
        assertEquals("Content hashing failed", response.errorMessage());
        assertEquals(1, response.assignmentResult().assignedCount());
        assertNull(response.hashingResult());
        assertFalse(response.completedWithIssues());
        assertEquals("FAILED", response.stages().get(3).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTENT_ASSIGNMENT", "CONTENT_HASHING"})
    void malformedFinalResultFailsBothReadsSafely(String type) throws Exception {
        var accepted = pending(List.of(source("malformed").id()));
        executor.runAccepted();
        jdbc.update("UPDATE job_stage SET result_json = 'private malformed result' WHERE job_id = ? AND stage_type = ?", accepted.job().id(), type);
        mvc.perform(get("/api/indexing-runs/" + accepted.scanRun().id())).andExpect(status().isInternalServerError()).andExpect(content().string(""));
        mvc.perform(get("/api/indexing-runs/source-status")).andExpect(status().isInternalServerError()).andExpect(content().string(""));
    }

    @Test
    void sourceCollectionIncludesNoHistoryMultiSourceAndLatestWithDeterministicTieBreak() throws Exception {
        Source first = source("first");
        Source second = source("second");
        Source idle = source("idle");
        var failed = pending(List.of(first.id(), second.id()));
        executor.held.clear();
        recovery.failIfActive(failed.job().id(), "Execution interrupted by application restart");
        var completed = pending(List.of(first.id(), second.id()));
        executor.runAccepted();
        // Equal durable creation times exercise the ScanRun-ID tiebreaker.
        jdbc.update("UPDATE scan_run SET created_at_ms = 1");
        var active = pending(List.of(second.id()));
        var before = jdbc.queryForList("SELECT * FROM job ORDER BY id");
        mvc.perform(get("/api/indexing-runs/source-status")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.active.jobId").value(active.job().id()))
                .andExpect(jsonPath("$.sources[0].sourceId").value(first.id()))
                .andExpect(jsonPath("$.sources[0].latest.scanRunId").value(completed.scanRun().id()))
                .andExpect(jsonPath("$.sources[0].latest.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sources[0].latest.stages").doesNotExist())
                .andExpect(jsonPath("$.sources[1].sourceId").value(second.id()))
                .andExpect(jsonPath("$.sources[1].latest.jobId").value(active.job().id()))
                .andExpect(jsonPath("$.sources[2].sourceId").value(idle.id()))
                .andExpect(jsonPath("$.sources[2].latest").isEmpty());
        assertEquals(before, jdbc.queryForList("SELECT * FROM job ORDER BY id"));
    }

    @Test
    void multiSourceActiveRunIsSharedAndCollectionUsesTwoQueriesRegardlessOfSourceCount() throws Exception {
        Source first = source("first");
        Source second = source("second");
        var active = pending(List.of(first.id(), second.id()));
        for (int index = 0; index < 50; index++) {
            String path = directory.resolve("unused-" + index).toString();
            catalog.insert(new Source(null, "Unused", path, path, 0, 1, 1));
        }
        jdbc.queries.set(0);
        mvc.perform(get("/api/indexing-runs/source-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active.jobId").value(active.job().id()))
                .andExpect(jsonPath("$.sources.length()").value(52))
                .andExpect(jsonPath("$.sources[0].latest.jobId").value(active.job().id()))
                .andExpect(jsonPath("$.sources[1].latest.jobId").value(active.job().id()));
        assertEquals(2, jdbc.queries.get());
    }

    private Source source(String name) throws Exception {
        Path root = Files.createDirectory(directory.resolve(name));
        Files.writeString(root.resolve("one.txt"), "content");
        return V3TestHost.boundSource(catalog, jdbc, root, name);
    }
    private IndexingRunDetails pending(List<Long> ids) { return starts.start(UUID.randomUUID().toString(), ids).run(); }
    private IndexingRunResponse detail(long id) throws Exception {
        var result = mvc.perform(get("/api/indexing-runs/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.readValue(result.getResponse().getContentAsByteArray(), IndexingRunResponse.class);
    }

    @TestConfiguration
    static class ReadHooks {
        @Bean @Primary CountingJdbc countingJdbc(DataSource dataSource) { return new CountingJdbc(dataSource); }
        @Bean @Primary ContentHashFileHasher issueHasher() {
            return new ContentHashFileHasher() {
                @Override public String hash(ContentHashCandidate candidate) throws IOException {
                    if (candidate.locationPath().contains("skipped.txt")) throw new StaleContentHashException(candidate.fileEntryId(), "fixture");
                    if (candidate.locationPath().contains("failed.txt")) throw new IOException("private filesystem detail");
                    return super.hash(candidate);
                }
            };
        }
    }
    static class CountingJdbc extends JdbcTemplate {
        final AtomicInteger queries = new AtomicInteger();
        CountingJdbc(DataSource source) { super(source); }
        @Override public <T> T query(String sql, ResultSetExtractor<T> extractor) {
            queries.incrementAndGet();
            return super.query(sql, extractor);
        }
    }
}
