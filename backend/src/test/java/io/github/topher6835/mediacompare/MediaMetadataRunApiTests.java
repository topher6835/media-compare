package io.github.topher6835.mediacompare;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.nio.file.Path;

import io.github.topher6835.mediacompare.analysis.MediaMetadataJobService;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResult;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResultCodec;
import io.github.topher6835.mediacompare.analysis.MediaMetadataExecutionState;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.Version2ScanExecutionService;
import io.github.topher6835.mediacompare.job.JobRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MediaMetadataJobTests.Hooks.class)
class MediaMetadataRunApiTests {

    @TempDir Path tempDir;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaMetadataJobService metadataJobs;
    @Autowired private JobRepository jobs;
    @Autowired private CatalogRepository catalog;
    @Autowired private ScanRunService scanRuns;
    @Autowired private Version2ScanExecutionService scanExecutions;
    @Autowired private io.github.topher6835.mediacompare.analysis.MediaMetadataExecutor executor;
    @Autowired private io.github.topher6835.mediacompare.MediaMetadataJobTests.ControllableExtractor extractor;
    @Autowired private ImageMetadataStageResultCodec stageCodec;
    @Autowired private MediaMetadataExecutionState executionState;

    @BeforeEach
    void clear() {
        for (String table : List.of("content_hash", "job_stage", "file_entry", "scan_run_source",
                "working_set_content", "analysis_record", "job", "scan_run", "working_set",
                "content_record", "source")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void startsAndReturnsTheNarrowPublicShape() throws Exception {
        mvc.perform(post("/api/media-metadata-runs"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/media-metadata-runs/[1-9][0-9]*")))
                .andExpect(jsonPath("$.jobId").isNumber())
                .andExpect(jsonPath("$.stage.status").value(org.hamcrest.Matchers.oneOf(
                        "PENDING", "RUNNING", "COMPLETED")));
    }

    @Test
    void readsCompletedSummaryWithoutLeakingStoredCodecFields() throws Exception {
        long id = metadataJobs.create().job().id();
        metadataJobs.run(id);

        mvc.perform(get("/api/media-metadata-runs/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stage.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stage.candidatesAttempted").value(0))
                .andExpect(jsonPath("$.stage.result.completedWithIssues").value(false))
                .andExpect(jsonPath("$.stage.result.version").doesNotExist());
    }

    @Test
    void readsPendingAndFailedJobs() throws Exception {
        long pending = metadataJobs.create().job().id();
        mvc.perform(get("/api/media-metadata-runs/" + pending))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stage.status").value("PENDING"))
                .andExpect(jsonPath("$.stage.result").doesNotExist());
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE job SET status='FAILED', current_stage_type=NULL, finished_at_ms=? WHERE id=?", now, pending);
        jdbc.update("UPDATE job_stage SET status='FAILED', finished_at_ms=?, error_message=? WHERE job_id=?",
                now, "test failure", pending);
        mvc.perform(get("/api/media-metadata-runs/" + pending))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage.status").value("FAILED"))
                .andExpect(jsonPath("$.stage.result").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.stage.errorMessage").value("test failure"));
    }

    @Test
    void readsRunningJob() throws Exception {
        var details = metadataJobs.create();
        executionState.start(details.job(), details.stages().getFirst(), System.currentTimeMillis());
        mvc.perform(get("/api/media-metadata-runs/" + details.job().id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.stage.status").value("RUNNING"))
                .andExpect(jsonPath("$.stage.result").doesNotExist());
    }

    @Test
    void completedWithIssuesIsReported() throws Exception {
        long id = metadataJobs.create().job().id();
        long now = System.currentTimeMillis();
        String result = stageCodec.write(new ImageMetadataStageResult(1, 2, 1, 0, 1, 0));
        jdbc.update("UPDATE job SET status='COMPLETED', current_stage_type=NULL, progress_completed=2, progress_total=2, started_at_ms=?, finished_at_ms=? WHERE id=?", now, now, id);
        jdbc.update("UPDATE job_stage SET status='COMPLETED', progress_completed=2, progress_total=2, started_at_ms=?, finished_at_ms=?, result_json=? WHERE job_id=?", now, now, result, id);
        mvc.perform(get("/api/media-metadata-runs/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage.result.completedWithIssues").value(true));
    }

    @Test
    void scanJobIdIsNotExposedThroughMetadataApi() throws Exception {
        Source source = catalog.insert(new Source(null, "scan", tempDir.toString(), tempDir.toString(), 1,
                System.currentTimeMillis(), System.currentTimeMillis()));
        long scanRunId = scanRuns.create(List.of(source.id())).scanRun().id();
        long scanJobId = scanExecutions.create(scanRunId).job().id();
        mvc.perform(get("/api/media-metadata-runs/" + scanJobId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void malformedPairAndTimestampAreInternalErrors() throws Exception {
        long pair = metadataJobs.create().job().id();
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE job SET status='COMPLETED', current_stage_type=NULL, started_at_ms=?, finished_at_ms=? WHERE id=?", now, now, pair);
        jdbc.update("UPDATE job_stage SET status='FAILED', finished_at_ms=? WHERE job_id=?", now, pair);
        mvc.perform(get("/api/media-metadata-runs/" + pair)).andExpect(status().isInternalServerError());
        long timestamp = metadataJobs.create().job().id();
        jdbc.update("UPDATE job SET status='RUNNING', started_at_ms=?, finished_at_ms=? WHERE id=?", now, now, timestamp);
        jdbc.update("UPDATE job_stage SET status='RUNNING', started_at_ms=? WHERE job_id=?", now, timestamp);
        mvc.perform(get("/api/media-metadata-runs/" + timestamp)).andExpect(status().isInternalServerError());
    }

    @Test
    void completedResultProgressMismatchIsAnInternalError() throws Exception {
        long id = metadataJobs.create().job().id();
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE job SET status='COMPLETED', current_stage_type=NULL, progress_completed=2, progress_total=2, started_at_ms=?, finished_at_ms=? WHERE id=?", now, now, id);
        jdbc.update("UPDATE job_stage SET status='COMPLETED', progress_completed=2, progress_total=2, started_at_ms=?, finished_at_ms=?, result_json=? WHERE job_id=?", now, now, stageCodec.write(new ImageMetadataStageResult(1, 1, 1, 0, 0, 0)), id);
        mvc.perform(get("/api/media-metadata-runs/" + id)).andExpect(status().isInternalServerError());
    }

    @Test
    void activeJobConflictsAndUnknownOrScanJobsAreNotExposed() throws Exception {
        long id = metadataJobs.create().job().id();
        mvc.perform(post("/api/media-metadata-runs"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/media-metadata-runs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        // Keep the accepted row referenced so this test also verifies the service boundary.
        org.junit.jupiter.api.Assertions.assertTrue(jobs.findJobById(id).isPresent());
    }

    @Test
    void schedulerRejectionMapsTo503AndFailsAcceptedMetadataJob() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch queued = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> { entered.countDown(); await(release); });
        assert entered.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.execute(queued::countDown);
        try {
            mvc.perform(post("/api/media-metadata-runs"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"));
            long jobId = jdbc.queryForObject("SELECT id FROM job WHERE job_type='MEDIA_METADATA'", Long.class);
            var job = jobs.findJobById(jobId).orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals("FAILED", job.status());
            org.junit.jupiter.api.Assertions.assertEquals("Execution could not be scheduled", job.errorMessage());
            org.junit.jupiter.api.Assertions.assertEquals("FAILED", jobs.findJobStagesByJobId(job.id()).getFirst().status());
            org.junit.jupiter.api.Assertions.assertEquals(0, extractor.calls);
        } finally {
            release.countDown();
            assert queued.await(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
