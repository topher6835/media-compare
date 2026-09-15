package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.DiscoveredFile;
import io.github.topher6835.mediacompare.scan.DiscoveryBatchWriter;
import io.github.topher6835.mediacompare.scan.DiscoveryFileWalker;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(DiscoveryTraversalFailureApiTests.FailingWalkerConfiguration.class)
class DiscoveryTraversalFailureApiTests {

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
    private TestDiscoveryFileWalker fileWalker;

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
        fileWalker.failOnCall(1, DiscoveryBatchWriter.MAX_BATCH_SIZE);
    }

    @Test
    void midTraversalFailureKeepsCommittedBatchesAndDoesNotAuthorizeMissingState() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("source"));
        String rootPath = root.toAbsolutePath().toString();
        Source source = catalogRepository.insert(new Source(null, "Source", rootPath, rootPath, 0, 1, 1));
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        ScanRunSource scanRunSource = scanRun.sources().getFirst();
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();
        catalogRepository.insert(new FileEntry(
                null, source.id(), "previous.dat", "previous.dat", null, "PRESENT", 10,
                1L, 0, 0, 1, 1, null, null));

        mockMvc.perform(post("/api/scan-runs/" + scanRun.scanRun().id() + "/execution/discovery"))
                .andExpect(status().isInternalServerError());

        assertEquals(DiscoveryBatchWriter.MAX_BATCH_SIZE + 1, countRows("file_entry"));
        assertEquals("PRESENT", catalogRepository.findFileEntryBySourceIdAndPathKey(source.id(), "previous.dat")
                .orElseThrow().presenceStatus());

        ScanRunSource failedSource = scanRepository.findScanRunSourceById(scanRunSource.id()).orElseThrow();
        assertEquals("FAILED", failedSource.status());
        assertEquals(1, failedSource.traversalGeneration());
        assertNull(failedSource.completedGeneration());
        assertNotNull(failedSource.completedAtMs());

        Job failedJob = jobRepository.findJobById(job.id()).orElseThrow();
        assertEquals("FAILED", failedJob.status());
        assertEquals(DiscoveryBatchWriter.MAX_BATCH_SIZE, failedJob.progressCompleted());
        assertNull(failedJob.progressTotal());
        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        assertEquals(1, stages.size());
        JobStage failedDiscovery = stages.getFirst();
        assertEquals("DISCOVERY", failedDiscovery.stageType());
        assertEquals("FAILED", failedDiscovery.status());
        assertEquals(DiscoveryBatchWriter.MAX_BATCH_SIZE, failedDiscovery.progressCompleted());
        assertNull(failedDiscovery.progressTotal());
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    @Test
    void multiSourceFailureFailsEveryParticipantInTheStartedAttempt() throws Exception {
        Path firstRoot = Files.createDirectory(temporaryDirectory.resolve("first"));
        Path failingRoot = Files.createDirectory(temporaryDirectory.resolve("failing"));
        Path laterRoot = Files.createDirectory(temporaryDirectory.resolve("later"));
        Source firstSource = insertSource("First", firstRoot);
        Source failingSource = insertSource("Failing", failingRoot);
        Source laterSource = insertSource("Later", laterRoot);
        ScanRunDetails scanRun = scanRunService.create(List.of(
                laterSource.id(), firstSource.id(), failingSource.id()));
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();
        catalogRepository.insert(new FileEntry(
                null, firstSource.id(), "known.dat", "known.dat", null, "PRESENT", 10,
                1L, 0, 0, 1, 1, null, null));
        fileWalker.failOnCall(2, 0);

        mockMvc.perform(post("/api/scan-runs/" + scanRun.scanRun().id() + "/execution/discovery"))
                .andExpect(status().isInternalServerError());

        assertEquals(List.of(firstRoot.toAbsolutePath(), failingRoot.toAbsolutePath()), fileWalker.walkedRoots());
        assertEquals("PRESENT", catalogRepository.findFileEntryBySourceIdAndPathKey(
                firstSource.id(), "observed/source-1.dat").orElseThrow().presenceStatus());
        assertEquals("PRESENT", catalogRepository.findFileEntryBySourceIdAndPathKey(
                firstSource.id(), "known.dat").orElseThrow().presenceStatus());

        List<ScanRunSource> failedSources = scanRepository.findScanRunSourcesByScanRunId(scanRun.scanRun().id());
        assertEquals(3, failedSources.size());
        Long failedAtMs = failedSources.getFirst().completedAtMs();
        String errorMessage = failedSources.getFirst().errorMessage();
        for (ScanRunSource failedSource : failedSources) {
            assertEquals("FAILED", failedSource.status());
            assertEquals(1, failedSource.traversalGeneration());
            assertNull(failedSource.completedGeneration());
            assertEquals(failedAtMs, failedSource.completedAtMs());
            assertEquals(errorMessage, failedSource.errorMessage());
            assertFalse("DISCOVERING".equals(failedSource.status()));
        }
        assertNotNull(failedAtMs);
        assertNotNull(errorMessage);
        assertTrue(errorMessage.contains("Source " + failingSource.id()));

        assertEquals("FAILED", scanRepository.findScanRunById(scanRun.scanRun().id()).orElseThrow().status());
        Job failedJob = jobRepository.findJobById(job.id()).orElseThrow();
        assertEquals("FAILED", failedJob.status());
        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        assertEquals(1, stages.size());
        assertEquals("DISCOVERY", stages.getFirst().stageType());
        assertEquals("FAILED", stages.getFirst().status());
        assertEquals(1, failedJob.progressCompleted());
        assertEquals(2, countRows("file_entry"));
    }

    private Source insertSource(String name, Path root) {
        String rootPath = root.toAbsolutePath().toString();
        return catalogRepository.insert(new Source(null, name, rootPath, rootPath, 0, 1, 1));
    }

    private int countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    @TestConfiguration
    static class FailingWalkerConfiguration {

        @Bean
        @Primary
        TestDiscoveryFileWalker failingDiscoveryFileWalker() {
            return new TestDiscoveryFileWalker();
        }
    }

    static class TestDiscoveryFileWalker extends DiscoveryFileWalker {

        private final List<Path> walkedRoots = new ArrayList<>();
        private int failingCall;
        private int observationsBeforeFailure;

        void failOnCall(int failingCall, int observationsBeforeFailure) {
            this.failingCall = failingCall;
            this.observationsBeforeFailure = observationsBeforeFailure;
            walkedRoots.clear();
        }

        List<Path> walkedRoots() {
            return List.copyOf(walkedRoots);
        }

        @Override
        public void walk(Path root, Consumer<DiscoveredFile> observer) throws IOException {
            walkedRoots.add(root);
            int callNumber = walkedRoots.size();
            if (callNumber < failingCall) {
                observer.accept(new DiscoveredFile("observed/source-" + callNumber + ".dat", 1, 100, 0));
                return;
            }
            if (callNumber == failingCall) {
                for (int index = 0; index < observationsBeforeFailure; index++) {
                    observer.accept(new DiscoveredFile("observed/failure-" + index + ".dat", index, 100, index));
                }
                throw new IOException("simulated traversal failure");
            }
            throw new AssertionError("Traversal continued after the configured failure");
        }
    }
}
