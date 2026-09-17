package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ImageIoMediaMetadataDefinition;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResult;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResultCodec;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataExecutionDetails;
import io.github.topher6835.mediacompare.analysis.MediaMetadataInterruptionRecovery;
import io.github.topher6835.mediacompare.analysis.MediaMetadataJobService;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.analysis.UnsupportedMediaMetadata;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.scan.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class Version2StartupTests {
    @TempDir Path directory;

    @Test
    void secondApplicationCannotRecoverLiveOwnerAndRestartRecoversBeforeWorkerAvailability() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("startup.db") + "?foreign_keys=on";
        long jobId;
        try (ConfigurableApplicationContext first = start(url)) {
            Job job = createPending(first);
            jobId = job.id();
            assertThrows(RuntimeException.class, () -> { try (var ignored = start(url)) {} });
            assertEquals("PENDING", first.getBean(JobRepository.class).findJobById(jobId).orElseThrow().status());
        }
        try (ConfigurableApplicationContext restarted = start(url)) {
            Job recovered = restarted.getBean(JobRepository.class).findJobById(jobId).orElseThrow();
            assertEquals("FAILED", recovered.status());
            assertEquals(Version2InterruptionRecovery.RESTART_MESSAGE, recovered.errorMessage());
            CountDownLatch workerChecked = new CountDownLatch(1);
            restarted.getBean(Version2IndexingExecutor.class).execute(() -> {
                if (restarted.getBean(JobRepository.class).findActiveVersion2ScanJobs().isEmpty()) {
                    workerChecked.countDown();
                }
            });
            assertTrue(workerChecked.await(10, TimeUnit.SECONDS));
        }
        try (CatalogOwnership released = CatalogOwnership.acquire(url)) {
            assertNotNull(released.lockPath());
        }
    }

    @Test
    void impossiblePersistedStateAbortsStartupAndReleasesOwnership() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("malformed.db") + "?foreign_keys=on";
        try (ConfigurableApplicationContext first = start(url)) {
            Job job = createPending(first);
            first.getBean(JdbcTemplate.class).update("DELETE FROM job_stage WHERE job_id = ?", job.id());
        }
        assertThrows(RuntimeException.class, () -> { try (var ignored = start(url)) {} });
        try (CatalogOwnership released = CatalogOwnership.acquire(url)) {
            assertNotNull(released.lockPath());
        }
    }

    @Test
    void restartRecoversMetadataJobAndAbandonedAnalysisThenProcessesRemainingContent()
            throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("metadata-startup.db") + "?foreign_keys=on";
        Path root = Files.createDirectory(directory.resolve("metadata-source"));
        Files.writeString(root.resolve("cached.bin"), "cached");
        Files.writeString(root.resolve("remaining.bin"), "remaining");
        Files.writeString(root.resolve("abandoned.bin"), "abandoned");
        long jobId;
        long cachedId;
        long remainingId;
        long abandonedId;
        try (ConfigurableApplicationContext first = start(url)) {
            Source source = first.getBean(CatalogRepository.class).insert(new Source(
                    null, "metadata", root.toString(), root.toString(), 0, 1, 1));
            ContentRecord cached = insertOccurrence(first, source, root, "cached.bin");
            ContentRecord remaining = insertOccurrence(first, source, root, "remaining.bin");
            ContentRecord abandoned = insertOccurrence(first, source, root, "abandoned.bin");
            cachedId = cached.id();
            remainingId = remaining.id();
            abandonedId = abandoned.id();
            insertCompletedMetadata(first, cached);
            insertAbandonedMetadata(first, abandoned);
            jobId = first.getBean(MediaMetadataJobService.class).create().job().id();
        }

        try (ConfigurableApplicationContext restarted = start(url)) {
            JobRepository jobs = restarted.getBean(JobRepository.class);
            assertEquals("FAILED", jobs.findJobById(jobId).orElseThrow().status());
            assertEquals(MediaMetadataInterruptionRecovery.RESTART_MESSAGE,
                    jobs.findJobById(jobId).orElseThrow().errorMessage());
            assertEquals("FAILED", jobs.findJobStagesByJobId(jobId).getFirst().status());
            assertEquals("COMPLETED", metadata(restarted, cachedId).status());
            assertEquals("FAILED", metadata(restarted, abandonedId).status());
            assertEquals(MediaMetadataInterruptionRecovery.ANALYSIS_RESTART_MESSAGE,
                    metadata(restarted, abandonedId).errorMessage());

            MediaMetadataJobService service = restarted.getBean(MediaMetadataJobService.class);
            MediaMetadataExecutionDetails accepted = service.create();
            MediaMetadataExecutionDetails completed = service.run(accepted.job().id());
            ImageMetadataStageResult summary = restarted.getBean(ImageMetadataStageResultCodec.class)
                    .read(completed.stages().getFirst().resultJson());
            assertEquals(new ImageMetadataStageResult(1, 2, 0, 2, 0, 0), summary);
            assertEquals(1, metadata(restarted, cachedId).attemptCount());
            assertEquals(1, metadata(restarted, remainingId).attemptCount());
            assertEquals(2, metadata(restarted, abandonedId).attemptCount());
        }
    }

    private Job createPending(ConfigurableApplicationContext context) {
        String path = directory.resolve("source").toString();
        Source source = context.getBean(CatalogRepository.class).insert(new Source(null, "source", path, path, 0, 1, 1));
        long scanId = context.getBean(ScanRunService.class).create(List.of(source.id())).scanRun().id();
        return context.getBean(Version2ScanExecutionService.class).create(scanId).job();
    }

    private ContentRecord insertOccurrence(
            ConfigurableApplicationContext context, Source source, Path root, String relativePath)
            throws Exception {
        Path file = root.resolve(relativePath);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        ContentRecord content = context.getBean(CatalogRepository.class).insert(
                new ContentRecord(null, attributes.size(), 1));
        Instant modified = attributes.lastModifiedTime().toInstant();
        context.getBean(CatalogRepository.class).insert(new FileEntry(
                null, source.id(), relativePath, relativePath, content.id(), "PRESENT",
                attributes.size(), modified.getEpochSecond(), modified.getNano(),
                0, 1, 1, null, null));
        return content;
    }

    private void insertCompletedMetadata(
            ConfigurableApplicationContext context, ContentRecord content) {
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        String resultJson = context.getBean(MediaMetadataResultCodec.class)
                .write(new UnsupportedMediaMetadata(1));
        context.getBean(AnalysisRepository.class).insert(new AnalysisRecord(
                null, content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), resultJson, "COMPLETED", 1,
                1, 1L, 2L, null));
    }

    private void insertAbandonedMetadata(
            ConfigurableApplicationContext context, ContentRecord content) {
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        context.getBean(AnalysisRepository.class).insert(new AnalysisRecord(
                null, content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), null, "RUNNING", 1,
                1, 1L, null, null));
    }

    private AnalysisRecord metadata(ConfigurableApplicationContext context, long contentRecordId) {
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        return context.getBean(AnalysisRepository.class).findAnalysisRecordByCacheKey(
                contentRecordId, MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash()).orElseThrow();
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(MediaCompareApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }
}
