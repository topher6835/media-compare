package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ImageIoImageMetadataExtractor;
import io.github.topher6835.mediacompare.analysis.ImageIoMediaMetadataDefinition;
import io.github.topher6835.mediacompare.analysis.ImageMetadataExtractionException;
import io.github.topher6835.mediacompare.analysis.ImageMetadataExtractor;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResult;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResultCodec;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataBackgroundService;
import io.github.topher6835.mediacompare.analysis.MediaMetadataExecutionDetails;
import io.github.topher6835.mediacompare.analysis.MediaMetadataExecutor;
import io.github.topher6835.mediacompare.analysis.MediaMetadataInterruptionRecovery;
import io.github.topher6835.mediacompare.analysis.MediaMetadataJobConflictException;
import io.github.topher6835.mediacompare.analysis.MediaMetadataJobService;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.analysis.MediaMetadataSchedulingException;
import io.github.topher6835.mediacompare.analysis.UnsupportedMediaMetadata;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.Version3ScanExecutionService;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import(MediaMetadataJobTests.Hooks.class)
class MediaMetadataJobTests {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogRepository catalog;

    @Autowired
    private AnalysisRepository analyses;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private MediaMetadataJobService metadataJobs;

    @Autowired
    private MediaMetadataBackgroundService background;

    @Autowired
    private MediaMetadataExecutor executor;

    @Autowired
    private MediaMetadataInterruptionRecovery recovery;

    @Autowired
    private ImageMetadataStageResultCodec stageResultCodec;

    @Autowired
    private MediaMetadataResultCodec metadataResultCodec;

    @Autowired
    private ControllableExtractor extractor;

    @Autowired
    private ScanRunService scanRuns;

    @Autowired
    private Version3ScanExecutionService scanExecutions;

    @BeforeEach
    void clear() {
        for (String table : List.of(
                "content_hash", "job_stage", "source_membership", "file_entry", "scan_run_source",
                "working_set_content", "analysis_record", "job", "scan_run",
                "working_set", "content_record", "source", "location_context")) {
            jdbc.update("DELETE FROM " + table);
        }
        extractor.reset();
    }

    @Test
    void processesMoreThanOneCandidatePageAndPersistsCompletedSummary() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("paged")).toRealPath();
        Source source = insertSource(root, "paged");
        Path seed = root.resolve("seed.png");
        writeImage(seed, 3, 2);
        for (int index = 0; index < 100; index++) {
            Path image = root.resolve("image-" + index + ".bin");
            Files.copy(seed, image);
            insertContentAndOccurrence(source, root, image.getFileName().toString());
        }
        Files.writeString(root.resolve("not-image.bin"), "not an image");
        insertContentAndOccurrence(source, root, "not-image.bin");
        Files.copy(seed, root.resolve("cached.bin"));
        ContentRecord cached = insertContentAndOccurrence(source, root, "cached.bin");
        insertCompleted(cached, new UnsupportedMediaMetadata(1));

        MediaMetadataExecutionDetails accepted = metadataJobs.create();
        MediaMetadataExecutionDetails completed = metadataJobs.run(accepted.job().id());

        assertEquals("COMPLETED", completed.job().status());
        assertNull(completed.job().scanRunId());
        assertEquals("MEDIA_METADATA", completed.job().jobType());
        assertEquals(1, completed.job().executionVersion());
        JobStage stage = completed.stages().getFirst();
        assertEquals("IMAGE_METADATA", stage.stageType());
        assertEquals("COMPLETED", stage.status());
        assertEquals(new ImageMetadataStageResult(1, 101, 100, 1, 0, 0),
                stageResultCodec.read(stage.resultJson()));
        assertEquals(102L, metadataArtifactCount());
        assertFalse(extractor.observedTransaction);
    }

    @Test
    void corruptImageRecordsFailureAndJobContinues() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("failure")).toRealPath();
        Source source = insertSource(root, "failure");
        Files.write(root.resolve("corrupt.bin"), new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9 });
        ContentRecord corrupt = insertContentAndOccurrence(source, root, "corrupt.bin");
        writeImage(root.resolve("valid.bin"), 5, 4);
        ContentRecord valid = insertContentAndOccurrence(source, root, "valid.bin");

        MediaMetadataExecutionDetails completed = runNewJob();

        assertEquals("COMPLETED", completed.job().status());
        assertEquals(new ImageMetadataStageResult(1, 2, 1, 0, 1, 0),
                result(completed));
        AnalysisRecord failed = compatible(corrupt);
        assertEquals("FAILED", failed.status());
        assertNull(failed.resultJson());
        assertEquals("Image metadata extraction failed", failed.errorMessage());
        assertEquals("COMPLETED", compatible(valid).status());
    }

    @Test
    void failedAnalysisRetriesToCompletionAndClearsItsError() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("retry")).toRealPath();
        Source source = insertSource(root, "retry");
        writeImage(root.resolve("retry.bin"), 5, 4);
        ContentRecord content = insertContentAndOccurrence(source, root, "retry.bin");
        extractor.failNames.add("retry.bin");

        runNewJob();
        assertEquals("FAILED", compatible(content).status());
        assertEquals(1, compatible(content).attemptCount());

        extractor.failNames.clear();
        MediaMetadataExecutionDetails retried = runNewJob();

        AnalysisRecord completed = compatible(content);
        assertEquals("COMPLETED", completed.status());
        assertEquals(2, completed.attemptCount());
        assertNull(completed.errorMessage());
        assertTrue(completed.resultJson().contains("AVAILABLE"));
        assertEquals(new ImageMetadataStageResult(1, 1, 1, 0, 0, 0), result(retried));
    }

    @Test
    void repeatedFailureIncrementsAttemptAndRemainsRetryable() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("repeat")).toRealPath();
        Source source = insertSource(root, "repeat");
        writeImage(root.resolve("repeat.bin"), 5, 4);
        ContentRecord content = insertContentAndOccurrence(source, root, "repeat.bin");
        extractor.failNames.add("repeat.bin");

        runNewJob();
        runNewJob();

        AnalysisRecord failed = compatible(content);
        assertEquals("FAILED", failed.status());
        assertEquals(2, failed.attemptCount());
        assertNull(failed.resultJson());
    }

    @Test
    void staleExtractionFailureFallsBackWithoutPublishingFailure() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("stale")).toRealPath();
        Source source = insertSource(root, "stale");
        writeImage(root.resolve("first.bin"), 7, 6);
        Files.copy(root.resolve("first.bin"), root.resolve("second.bin"));
        ContentRecord content = insertContentAndOccurrences(
                source, root, List.of("first.bin", "second.bin"));
        extractor.mutateAndFailNames.add("first.bin");

        MediaMetadataExecutionDetails completed = runNewJob();

        assertEquals(new ImageMetadataStageResult(1, 1, 1, 0, 0, 0), result(completed));
        assertEquals("COMPLETED", compatible(content).status());
        assertEquals(1, compatible(content).attemptCount());
    }

    @Test
    void unexpectedExtractorFailureFailsStageAndJob() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("infrastructure-failure")).toRealPath();
        Source source = insertSource(root, "infrastructure-failure");
        writeImage(root.resolve("broken-worker.bin"), 3, 3);
        insertContentAndOccurrence(source, root, "broken-worker.bin");
        extractor.unexpectedFailureNames.add("broken-worker.bin");
        Job accepted = metadataJobs.create().job();

        assertThrows(IllegalStateException.class, () -> metadataJobs.run(accepted.id()));

        assertEquals("FAILED", jobs.findJobById(accepted.id()).orElseThrow().status());
        assertEquals("Image metadata processing failed",
                jobs.findJobById(accepted.id()).orElseThrow().errorMessage());
        assertEquals("FAILED", jobs.findJobStagesByJobId(accepted.id()).getFirst().status());
        assertEquals(0L, metadataArtifactCount());
    }

    @Test
    void admissionIsSeparateFromScanAndTerminalJobsReleaseIt() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("admission")).toRealPath();
        Source source = insertSource(root, "admission");
        writeImage(root.resolve("one.bin"), 2, 2);
        insertContentAndOccurrence(source, root, "one.bin");
        Job first = metadataJobs.create().job();

        assertThrows(MediaMetadataJobConflictException.class, metadataJobs::create);
        long scanRunId = scanRuns.create(List.of(source.id())).scanRun().id();
        assertEquals("PENDING", scanExecutions.create(scanRunId).job().status());

        metadataJobs.run(first.id());
        assertEquals("PENDING", metadataJobs.create().job().status());
    }

    @Test
    void simultaneousMetadataCreationAdmitsExactlyOneJob() throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> createAfter(gate));
            var second = callers.submit(() -> createAfter(gate));

            assertEquals(List.of("conflict", "created"), java.util.stream.Stream.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .sorted().toList());
        }
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM job WHERE job_type = 'MEDIA_METADATA'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_stage WHERE stage_type = 'IMAGE_METADATA'", Long.class));
    }

    @Test
    void activeStageIsObservableWhileBackgroundExtractionRuns() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("background")).toRealPath();
        Source source = insertSource(root, "background");
        writeImage(root.resolve("one.bin"), 2, 2);
        insertContentAndOccurrence(source, root, "one.bin");
        extractor.blockEntered = new CountDownLatch(1);
        extractor.blockRelease = new CountDownLatch(1);

        MediaMetadataExecutionDetails accepted = background.start();
        assertTrue(extractor.blockEntered.await(10, TimeUnit.SECONDS));
        assertEquals("RUNNING", jobs.findJobById(accepted.job().id()).orElseThrow().status());
        assertEquals("RUNNING", jobs.findJobStageByJobIdAndType(
                accepted.job().id(), "IMAGE_METADATA").orElseThrow().status());

        extractor.blockRelease.countDown();
        awaitTerminal(accepted.job().id());
        assertEquals("COMPLETED", jobs.findJobById(accepted.job().id()).orElseThrow().status());
    }

    @Test
    void rejectedSchedulingFailsAcceptedJobAndStageWithoutCallerExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queued = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            await(release);
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        executor.execute(queued::countDown);

        try {
            assertThrows(MediaMetadataSchedulingException.class, background::start);
            Job job = jdbc.queryForObject(
                    "SELECT id FROM job WHERE job_type = 'MEDIA_METADATA'",
                    (resultSet, rowNumber) -> jobs.findJobById(resultSet.getLong("id")).orElseThrow());
            assertEquals("FAILED", job.status());
            assertEquals("Execution could not be scheduled", job.errorMessage());
            assertEquals("FAILED", jobs.findJobStagesByJobId(job.id()).getFirst().status());
            assertEquals(0, extractor.calls);
        } finally {
            release.countDown();
            assertTrue(queued.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void exactDefinitionRecoveryMakesOnlyAbandonedImageIoRowsRetryable() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("analysis-recovery")).toRealPath();
        Source source = insertSource(root, "analysis-recovery");
        writeImage(root.resolve("pending.bin"), 2, 2);
        ContentRecord pending = insertContentAndOccurrence(source, root, "pending.bin");
        writeImage(root.resolve("running.bin"), 2, 2);
        ContentRecord running = insertContentAndOccurrence(source, root, "running.bin");
        writeImage(root.resolve("other.bin"), 2, 2);
        ContentRecord other = insertContentAndOccurrence(source, root, "other.bin");
        insertNonterminal(pending, ImageIoMediaMetadataDefinition.definition(), "PENDING", 0);
        insertNonterminal(running, ImageIoMediaMetadataDefinition.definition(), "RUNNING", 1);
        insertNonterminal(other,
                new MediaMetadataAnalysisDefinition("other", "1", 1, "other-hash", "{}"),
                "RUNNING", 1);
        insertCompleted(other, new UnsupportedMediaMetadata(1));

        recovery.recoverAtStartup();

        assertEquals("FAILED", compatible(pending).status());
        assertEquals("FAILED", compatible(running).status());
        assertEquals("RUNNING", analyses.findAnalysisRecordByCacheKey(
                other.id(), "MEDIA_METADATA", "other", "1", 1, "other-hash")
                .orElseThrow().status());
        MediaMetadataExecutionDetails completed = runNewJob();
        assertEquals(2, result(completed).candidatesAttempted());
    }

    private MediaMetadataExecutionDetails runNewJob() {
        MediaMetadataExecutionDetails accepted = metadataJobs.create();
        return metadataJobs.run(accepted.job().id());
    }

    private String createAfter(CyclicBarrier gate) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        try {
            metadataJobs.create();
            return "created";
        } catch (MediaMetadataJobConflictException exception) {
            return "conflict";
        }
    }

    private ImageMetadataStageResult result(MediaMetadataExecutionDetails details) {
        return stageResultCodec.read(details.stages().getFirst().resultJson());
    }

    private void awaitTerminal(long jobId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (List.of("COMPLETED", "FAILED").contains(
                    jobs.findJobById(jobId).orElseThrow().status())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Metadata Job did not become terminal");
    }

    private Source insertSource(Path root, String name) {
        Source source = catalog.insert(new Source(
                null, name, root.toString(), root.toString(), 0, 1, 1));
        LocationPath location = LocationPathParser.parse(LocationDialect.UNIX, root.toString());
        String contextId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String volumeId = "11111111-2222-3333-4444-555555555555";
        var contextEvidence = new MacOsApfsLocationContextEvidence(1,
                MacOsApfsLocationContextEvidence.PROFILE, 1,
                location, LocationKeyCodec.encode(location), "apfs", volumeId, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, contextId, 1, contextEvidence));
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 1, ?, 1, 1)
                """, contextId, new LocationPathCodec().encode(location),
                LocationKeyCodec.encode(location).value(), acceptance);
        var rootEvidence = new MacOsApfsSourceRootEvidence(1,
                MacOsApfsSourceRootEvidence.PROFILE, 1, contextId, 1, 1,
                location, LocationKeyCodec.encode(location), volumeId, "10",
                new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(
                new SourceBindingEvidence(1, source.id(), rootEvidence));
        jdbc.update("""
                UPDATE source SET root_path_key = ?, root_path_dialect = 'unix',
                    bound_location_context_id = ?, binding_evidence_json = ?, location_revision = 1
                WHERE id = ?
                """, LocationKeyCodec.encode(location).value(), contextId, binding, source.id());
        return catalog.findSourceById(source.id()).orElseThrow();
    }

    private ContentRecord insertContentAndOccurrence(
            Source source, Path root, String relativePath) throws IOException {
        return insertContentAndOccurrences(source, root, List.of(relativePath));
    }

    private ContentRecord insertContentAndOccurrences(
            Source source, Path root, List<String> relativePaths) throws IOException {
        BasicFileAttributes first = Files.readAttributes(
                root.resolve(relativePaths.getFirst()), BasicFileAttributes.class);
        ContentRecord content = catalog.insert(new ContentRecord(null, first.size(), 1));
        for (String relativePath : relativePaths) {
            BasicFileAttributes attributes = Files.readAttributes(
                    root.resolve(relativePath), BasicFileAttributes.class);
            assertEquals(content.sizeBytes(), attributes.size());
            Instant modified = attributes.lastModifiedTime().toInstant();
            LocationPath fileLocation = LocationPathParser.parse(LocationDialect.UNIX,
                    root.resolve(relativePath).toString());
            FileEntry entry = catalog.insert(new FileEntry(
                    null, "RESOLVED", source.boundLocationContextId(),
                    new LocationPathCodec().encode(fileLocation),
                    LocationKeyCodec.encode(fileLocation).value(), content.id(),
                    attributes.size(), modified.getEpochSecond(), modified.getNano(),
                    FileExtensionNormalizer.fromRelativePath(relativePath), 0, 1, 1));
            jdbc.update("""
                    INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                        path_key, applicability_status, presence_status,
                        observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                        observed_source_location_revision, observed_location_context_revision)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 'PRESENT', 0, 1, 1, 1, 1)
                    """, source.id(), entry.id(), relativePath, relativePath);
        }
        return content;
    }

    private void insertCompleted(ContentRecord content, UnsupportedMediaMetadata result) {
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        analyses.insert(new AnalysisRecord(
                null, content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), metadataResultCodec.write(result),
                "COMPLETED", 1, 1, 1L, 2L, null));
    }

    private void insertNonterminal(
            ContentRecord content,
            MediaMetadataAnalysisDefinition definition,
            String status,
            long attemptCount) {
        analyses.insert(new AnalysisRecord(
                null, content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), null, status, attemptCount,
                1, "RUNNING".equals(status) ? 1L : null, null, null));
    }

    private AnalysisRecord compatible(ContentRecord content) {
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        return analyses.findAnalysisRecordByCacheKey(
                content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash()).orElseThrow();
    }

    private long metadataArtifactCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis_record WHERE analysis_type = 'MEDIA_METADATA'",
                Long.class);
    }

    private static void writeImage(Path path, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration
    static class Hooks {

        @Bean
        @Primary
        ControllableExtractor controllableExtractor() {
            return new ControllableExtractor();
        }
    }

    static class ControllableExtractor implements ImageMetadataExtractor {
        private final ImageIoImageMetadataExtractor delegate = new ImageIoImageMetadataExtractor();
        final Set<String> failNames = new HashSet<>();
        final Set<String> mutateAndFailNames = new HashSet<>();
        final Set<String> unexpectedFailureNames = new HashSet<>();
        volatile CountDownLatch blockEntered = new CountDownLatch(0);
        volatile CountDownLatch blockRelease = new CountDownLatch(0);
        volatile boolean observedTransaction;
        volatile int calls;

        @Override
        public io.github.topher6835.mediacompare.analysis.MediaMetadataResult extract(Path file) {
            calls++;
            observedTransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            blockEntered.countDown();
            await(blockRelease);
            if (mutateAndFailNames.contains(file.getFileName().toString())) {
                try {
                    Files.writeString(file, "changed during extraction");
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
                throw failure();
            }
            if (failNames.contains(file.getFileName().toString())) {
                throw failure();
            }
            if (unexpectedFailureNames.contains(file.getFileName().toString())) {
                throw new IllegalStateException("simulated infrastructure failure");
            }
            return delegate.extract(file);
        }

        void reset() {
            failNames.clear();
            mutateAndFailNames.clear();
            unexpectedFailureNames.clear();
            blockEntered = new CountDownLatch(0);
            blockRelease = new CountDownLatch(0);
            observedTransaction = false;
            calls = 0;
        }

        private static ImageMetadataExtractionException failure() {
            return new ImageMetadataExtractionException(
                    "simulated failure", new IOException("simulated"));
        }
    }
}
