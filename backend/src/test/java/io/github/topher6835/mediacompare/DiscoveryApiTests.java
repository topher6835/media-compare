package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.DiscoveryBatchWriter;
import io.github.topher6835.mediacompare.scan.DiscoveryExecutionState;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class DiscoveryApiTests {

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
    void discoversRecursiveRegularFilesAndPreparesReconciliation() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path emptyDirectory = Files.createDirectory(root.resolve("empty"));
        Path topFile = Files.writeString(root.resolve("Top.txt"), "top");
        Path nestedDirectory = Files.createDirectories(root.resolve("Nested").resolve("More"));
        Path nestedFile = Files.write(nestedDirectory.resolve("Clip.bin"), new byte[] { 1, 2, 3, 4, 5 });

        ExecutionFixture fixture = createExecution(root);
        catalogRepository.insert(new FileEntry(
                null, fixture.source().id(), "gone.dat", "gone.dat", null, "MISSING", 10,
                1L, 0, 3, 1, 1, null, null));

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStageType").value("RECONCILIATION"))
                .andExpect(jsonPath("$.progressCompleted").value(2))
                .andExpect(jsonPath("$.progressTotal").value(2))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.stages[0].stageType").value("DISCOVERY"))
                .andExpect(jsonPath("$.stages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.stages[0].progressCompleted").value(2))
                .andExpect(jsonPath("$.stages[0].progressTotal").value(2))
                .andExpect(jsonPath("$.stages[0].attemptCount").value(1))
                .andExpect(jsonPath("$.stages[1].stageType").value("RECONCILIATION"))
                .andExpect(jsonPath("$.stages[1].status").value("PENDING"))
                .andExpect(jsonPath("$.stages[1].progressCompleted").value(0))
                .andExpect(jsonPath("$.stages[1].progressTotal").isEmpty())
                .andExpect(jsonPath("$.stages[1].attemptCount").value(0));

        FileEntry topEntry = requireFile(fixture.source(), "Top.txt");
        FileEntry nestedEntry = requireFile(fixture.source(), "Nested/More/Clip.bin");
        assertNewObservation(topEntry, topFile, fixture.scanRunSource());
        assertNewObservation(nestedEntry, nestedFile, fixture.scanRunSource());
        assertEquals("Nested/More/Clip.bin", nestedEntry.relativePath());
        assertEquals(nestedEntry.relativePath(), nestedEntry.pathKey());
        assertFalse(Path.of(nestedEntry.relativePath()).isAbsolute());
        assertFalse(nestedEntry.relativePath().contains(root.toString()));
        assertEquals(3, countRows("file_entry"));
        assertTrue(catalogRepository.findFileEntryBySourceIdAndPathKey(
                fixture.source().id(), emptyDirectory.getFileName().toString()).isEmpty());
        assertEquals("MISSING", requireFile(fixture.source(), "gone.dat").presenceStatus());

        ScanRun scanRun = scanRepository.findScanRunById(fixture.scanRun().id()).orElseThrow();
        assertEquals("RUNNING", scanRun.status());
        assertNotNull(scanRun.startedAtMs());
        assertNull(scanRun.finishedAtMs());
        assertNull(scanRun.errorMessage());

        ScanRunSource scanRunSource = scanRepository.findScanRunSourceById(fixture.scanRunSource().id())
                .orElseThrow();
        assertEquals("DISCOVERED", scanRunSource.status());
        assertEquals(1, scanRunSource.traversalGeneration());
        assertNull(scanRunSource.completedGeneration());
        assertNotNull(scanRunSource.startedAtMs());
        assertNull(scanRunSource.completedAtMs());
        assertNull(scanRunSource.errorMessage());

        Job job = jobRepository.findJobById(fixture.job().id()).orElseThrow();
        assertEquals("RUNNING", job.status());
        assertEquals("RECONCILIATION", job.currentStageType());
        assertEquals(2, job.progressCompleted());
        assertEquals(2L, job.progressTotal());
        assertEquals(1, job.attemptCount());
        assertNotNull(job.startedAtMs());
        assertNull(job.finishedAtMs());
        assertNull(job.errorMessage());

        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        assertEquals(2, stages.size());
        JobStage discovery = stages.get(0);
        assertEquals("COMPLETED", discovery.status());
        assertEquals(2, discovery.progressCompleted());
        assertEquals(2L, discovery.progressTotal());
        assertEquals(1, discovery.attemptCount());
        assertNotNull(discovery.startedAtMs());
        assertNotNull(discovery.finishedAtMs());
        assertNull(discovery.errorMessage());
        JobStage reconciliation = stages.get(1);
        assertEquals("RECONCILIATION", reconciliation.stageType());
        assertEquals("PENDING", reconciliation.status());
        assertEquals(discovery.finishedAtMs().longValue(), reconciliation.createdAtMs());
        assertNull(reconciliation.startedAtMs());
        assertNull(reconciliation.finishedAtMs());
        assertNull(reconciliation.errorMessage());

        assertEquals(0, countRows("content_record"));
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    @Test
    void reobservingFilesPreservesOrInvalidatesContentConservatively() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("observations"));
        Path unchangedFile = Files.writeString(root.resolve("Case.txt"), "same");
        Path changedSizeFile = Files.writeString(root.resolve("size.txt"), "new size");
        Path changedTimeFile = Files.writeString(root.resolve("time.txt"), "same size");
        Path reappearedFile = Files.writeString(root.resolve("back.txt"), "back");
        ExecutionFixture fixture = createExecution(root);

        BasicFileAttributes unchangedAttributes = attributes(unchangedFile);
        BasicFileAttributes changedSizeAttributes = attributes(changedSizeFile);
        BasicFileAttributes changedTimeAttributes = attributes(changedTimeFile);
        BasicFileAttributes reappearedAttributes = attributes(reappearedFile);
        ContentRecord unchangedContent = insertContent(unchangedAttributes.size());
        ContentRecord sizeContent = insertContent(changedSizeAttributes.size());
        ContentRecord timeContent = insertContent(changedTimeAttributes.size());
        ContentRecord reappearedContent = insertContent(reappearedAttributes.size());

        catalogRepository.insert(existingEntry(fixture.source(), "old spelling", "Case.txt", unchangedContent.id(),
                "PRESENT", unchangedAttributes.size(), unchangedAttributes, 4));
        catalogRepository.insert(existingEntry(fixture.source(), "size.txt", "size.txt", sizeContent.id(),
                "PRESENT", changedSizeAttributes.size() + 1, changedSizeAttributes, 7));
        catalogRepository.insert(new FileEntry(
                null, fixture.source().id(), "time.txt", "time.txt", timeContent.id(), "PRESENT",
                changedTimeAttributes.size(), changedTimeAttributes.lastModifiedTime().toInstant().getEpochSecond() - 1,
                changedTimeAttributes.lastModifiedTime().toInstant().getNano(), 9, 10, 11, null, null));
        catalogRepository.insert(existingEntry(fixture.source(), "back.txt", "back.txt", reappearedContent.id(),
                "MISSING", reappearedAttributes.size(), reappearedAttributes, 2));

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressCompleted").value(4));

        FileEntry unchanged = requireFile(fixture.source(), "Case.txt");
        assertEquals("Case.txt", unchanged.relativePath());
        assertEquals(4, unchanged.observationRevision());
        assertEquals(unchangedContent.id(), unchanged.currentContentId());
        assertEquals(10, unchanged.firstSeenAtMs());

        FileEntry changedSize = requireFile(fixture.source(), "size.txt");
        assertEquals(8, changedSize.observationRevision());
        assertNull(changedSize.currentContentId());

        FileEntry changedTime = requireFile(fixture.source(), "time.txt");
        assertEquals(10, changedTime.observationRevision());
        assertNull(changedTime.currentContentId());

        FileEntry reappeared = requireFile(fixture.source(), "back.txt");
        assertEquals("PRESENT", reappeared.presenceStatus());
        assertEquals(3, reappeared.observationRevision());
        assertNull(reappeared.currentContentId());
        assertEquals(4, countRows("content_record"));
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));
    }

    @Test
    void sourceRevisionMismatchConflictsBeforeAnyMutationOrTraversal() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("revision"));
        Files.writeString(root.resolve("file.txt"), "data");
        ExecutionFixture fixture = createExecution(root);
        jdbcTemplate.update("UPDATE source SET location_revision = location_revision + 1 WHERE id = ?",
                fixture.source().id());

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id())))
                .andExpect(status().isConflict());

        assertEquals("PENDING", scanRepository.findScanRunById(fixture.scanRun().id()).orElseThrow().status());
        ScanRunSource scanRunSource = scanRepository.findScanRunSourceById(fixture.scanRunSource().id())
                .orElseThrow();
        assertEquals("PENDING", scanRunSource.status());
        assertEquals(0, scanRunSource.traversalGeneration());
        Job job = jobRepository.findJobById(fixture.job().id()).orElseThrow();
        assertEquals("PENDING", job.status());
        assertEquals(0, job.attemptCount());
        assertEquals("PENDING", jobRepository.findJobStagesByJobId(job.id()).getFirst().status());
        assertEquals(0, countRows("file_entry"));
    }

    @Test
    void unknownScanRunAndMissingExecutionReturnNotFound() throws Exception {
        mockMvc.perform(post(discoveryPath(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());

        Path root = Files.createDirectory(temporaryDirectory.resolve("no-execution"));
        Source source = insertSource(root);
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        mockMvc.perform(post(discoveryPath(scanRun.scanRun().id())))
                .andExpect(status().isNotFound());
    }

    @Test
    void completedDiscoveryCannotRunAgain() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("once"));
        Files.writeString(root.resolve("file.txt"), "data");
        ExecutionFixture fixture = createExecution(root);

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id()))).andExpect(status().isOk());
        mockMvc.perform(post(discoveryPath(fixture.scanRun().id()))).andExpect(status().isConflict());

        assertEquals(1, countRows("file_entry"));
        assertEquals(2, countRows("job_stage"));
    }

    @Test
    void missingSourceRootPersistsFailureWithoutReconciliation() throws Exception {
        Path missingRoot = temporaryDirectory.resolve("missing").toAbsolutePath();
        ExecutionFixture fixture = createExecution(missingRoot);

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id())))
                .andExpect(status().isInternalServerError());

        ScanRun scanRun = scanRepository.findScanRunById(fixture.scanRun().id()).orElseThrow();
        assertEquals("FAILED", scanRun.status());
        assertNotNull(scanRun.startedAtMs());
        assertNotNull(scanRun.finishedAtMs());
        assertNotNull(scanRun.errorMessage());

        ScanRunSource source = scanRepository.findScanRunSourceById(fixture.scanRunSource().id()).orElseThrow();
        assertEquals("FAILED", source.status());
        assertEquals(1, source.traversalGeneration());
        assertNull(source.completedGeneration());
        assertNotNull(source.startedAtMs());
        assertNotNull(source.completedAtMs());
        assertNotNull(source.errorMessage());

        Job job = jobRepository.findJobById(fixture.job().id()).orElseThrow();
        assertEquals("FAILED", job.status());
        assertEquals("DISCOVERY", job.currentStageType());
        assertEquals(1, job.attemptCount());
        assertNotNull(job.startedAtMs());
        assertNotNull(job.finishedAtMs());
        assertNotNull(job.errorMessage());
        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        assertEquals(1, stages.size());
        assertEquals("FAILED", stages.getFirst().status());
        assertEquals(1, stages.getFirst().attemptCount());
        assertNotNull(stages.getFirst().startedAtMs());
        assertNotNull(stages.getFirst().finishedAtMs());
        assertNotNull(stages.getFirst().errorMessage());
        assertEquals(0, countRows("file_entry"));
    }

    @Test
    void handlesMoreThanOneBatch() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("large"));
        int fileCount = DiscoveryBatchWriter.MAX_BATCH_SIZE + 1;
        for (int index = 0; index < fileCount; index++) {
            Files.writeString(root.resolve("file-" + index + ".txt"), "x");
        }
        ExecutionFixture fixture = createExecution(root);

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressCompleted").value(fileCount))
                .andExpect(jsonPath("$.progressTotal").value(fileCount));

        assertEquals(fileCount, countRows("file_entry"));
        JobStage discovery = jobRepository.findJobStagesByJobId(fixture.job().id()).getFirst();
        assertEquals(fileCount, discovery.progressCompleted());
        assertEquals((long) fileCount, discovery.progressTotal());
    }

    @Test
    void skipsSymbolicLinkEntriesWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("links"));
        Path target = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        try {
            Files.createSymbolicLink(root.resolve("linked.txt"), target);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }
        ExecutionFixture fixture = createExecution(root);

        mockMvc.perform(post(discoveryPath(fixture.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressCompleted").value(0))
                .andExpect(jsonPath("$.progressTotal").value(0));

        assertEquals(0, countRows("file_entry"));
    }

    @Test
    void transactionBoundariesAreAppliedThroughDedicatedBeans() throws Exception {
        assertNotNull(DiscoveryBatchWriter.class
                .getMethod("write", long.class, long.class, List.class, long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(DiscoveryExecutionState.class
                .getMethod("start", long.class, Job.class, JobStage.class, List.class, long.class)
                .getAnnotation(Transactional.class));
        assertNull(ScanExecutionService.class
                .getMethod("executeDiscovery", long.class)
                .getAnnotation(Transactional.class));
    }

    private ExecutionFixture createExecution(Path root) {
        Source source = insertSource(root);
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();
        return new ExecutionFixture(source, scanRun.scanRun(), scanRun.sources().getFirst(), job);
    }

    private Source insertSource(Path root) {
        String rootPath = root.toAbsolutePath().toString();
        return catalogRepository.insert(new Source(null, "Source", rootPath, rootPath, 0, 1, 1));
    }

    private ContentRecord insertContent(long size) {
        return catalogRepository.insert(new ContentRecord(null, size, 1));
    }

    private FileEntry existingEntry(Source source, String relativePath, String pathKey, long contentId,
            String presence, long size, BasicFileAttributes attributes, long revision) {
        return new FileEntry(
                null,
                source.id(),
                relativePath,
                pathKey,
                contentId,
                presence,
                size,
                attributes.lastModifiedTime().toInstant().getEpochSecond(),
                attributes.lastModifiedTime().toInstant().getNano(),
                revision,
                10,
                11,
                null,
                null);
    }

    private FileEntry requireFile(Source source, String pathKey) {
        return catalogRepository.findFileEntryBySourceIdAndPathKey(source.id(), pathKey).orElseThrow();
    }

    private void assertNewObservation(FileEntry entry, Path file, ScanRunSource scanRunSource) throws IOException {
        BasicFileAttributes expected = attributes(file);
        assertEquals("PRESENT", entry.presenceStatus());
        assertNull(entry.currentContentId());
        assertEquals(0, entry.observationRevision());
        assertEquals(expected.size(), entry.sizeBytes());
        assertEquals(expected.lastModifiedTime().toInstant().getEpochSecond(), entry.modifiedTimeEpochSecond());
        assertEquals(expected.lastModifiedTime().toInstant().getNano(), entry.modifiedTimeNano());
        assertTrue(entry.firstSeenAtMs() > 0);
        assertTrue(entry.lastSeenAtMs() >= entry.firstSeenAtMs());
        assertEquals(scanRunSource.id(), entry.lastSeenScanRunSourceId());
        assertEquals(1L, entry.lastSeenTraversalGeneration());
    }

    private BasicFileAttributes attributes(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class);
    }

    private String discoveryPath(long scanRunId) {
        return "/api/scan-runs/" + scanRunId + "/execution/discovery";
    }

    private int countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private record ExecutionFixture(Source source, ScanRun scanRun, ScanRunSource scanRunSource, Job job) {
    }
}
