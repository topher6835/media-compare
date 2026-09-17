package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
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

    private Job createPending(ConfigurableApplicationContext context) {
        String path = directory.resolve("source").toString();
        Source source = context.getBean(CatalogRepository.class).insert(new Source(null, "source", path, path, 0, 1, 1));
        long scanId = context.getBean(ScanRunService.class).create(List.of(source.id())).scanRun().id();
        return context.getBean(Version2ScanExecutionService.class).create(scanId).job();
    }

    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(MediaCompareApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }
}
