package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentCandidate;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentWriter;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.StaleContentAssignmentException;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ContentAssignmentService;
import io.github.topher6835.mediacompare.scan.ReconciliationService;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class ContentAssignmentApiTests {

    private static final int PAGE_SIZE = 250;

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
    private ReconciliationService reconciliationService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ContentAssignmentWriter contentAssignmentWriter;

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
    void assignsDistinctContentRecordsWithoutMutatingCompletedExecutionOrUsingTheFilesystem() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("selected"));
        Path firstPath = Files.writeString(root.resolve("first.dat"), "identical");
        Path secondPath = Files.writeString(root.resolve("second.dat"), "identical");
        Path assignedPath = Files.writeString(root.resolve("assigned.dat"), "assigned");
        FileTime sharedModifiedTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_000_000));
        Files.setLastModifiedTime(firstPath, sharedModifiedTime);
        Files.setLastModifiedTime(secondPath, sharedModifiedTime);

        Source selectedSource = insertSource("Selected", root);
        Source outsideSource = insertSource("Outside", temporaryDirectory.resolve("outside-not-created"));
        ScanRunDetails scanRun = scanRunService.create(List.of(selectedSource.id()));
        ScanRunSource scanRunSource = scanRun.sources().getFirst();
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();

        BasicFileAttributes assignedAttributes = Files.readAttributes(assignedPath, BasicFileAttributes.class);
        ContentRecord existingContent = catalogRepository.insert(new ContentRecord(
                null, assignedAttributes.size(), 50));
        catalogRepository.insert(new FileEntry(
                null, selectedSource.id(), "assigned.dat", "assigned.dat", existingContent.id(), "PRESENT",
                assignedAttributes.size(),
                assignedAttributes.lastModifiedTime().toInstant().getEpochSecond(),
                assignedAttributes.lastModifiedTime().toInstant().getNano(),
                4, 10, 20, null, null));

        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        reconciliationService.execute(scanRun.scanRun().id());

        FileEntry firstBefore = requireFile(selectedSource, "first.dat");
        FileEntry secondBefore = requireFile(selectedSource, "second.dat");
        FileEntry assignedBefore = requireFile(selectedSource, "assigned.dat");
        FileEntry missing = catalogRepository.insert(new FileEntry(
                null, selectedSource.id(), "missing.dat", "missing.dat", null, "MISSING", 9,
                100L, 200, 2, 11, 21, scanRunSource.id(), 1L));
        FileEntry stale = catalogRepository.insert(new FileEntry(
                null, selectedSource.id(), "stale.dat", "stale.dat", null, "PRESENT", 9,
                100L, 200, 2, 11, 21, scanRunSource.id(), 99L));
        FileEntry outside = catalogRepository.insert(new FileEntry(
                null, outsideSource.id(), "outside.dat", "outside.dat", null, "PRESENT", 9,
                100L, 200, 2, 11, 21, null, null));
        DurableExecution completedExecution = durableExecution(scanRun.scanRun().id(), job.id());
        long contentCountBefore = countRows("content_record");

        jdbcTemplate.update("UPDATE source SET location_revision = location_revision + 1 WHERE id = ?",
                selectedSource.id());
        Files.delete(firstPath);
        Files.delete(secondPath);
        Files.delete(assignedPath);
        Files.delete(root);

        mockMvc.perform(post(assignmentPath(scanRun.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanRunId").value(scanRun.scanRun().id()))
                .andExpect(jsonPath("$.assignedCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0));

        FileEntry firstAssigned = requireFile(selectedSource, "first.dat");
        FileEntry secondAssigned = requireFile(selectedSource, "second.dat");
        assertNotNull(firstAssigned.currentContentId());
        assertNotNull(secondAssigned.currentContentId());
        assertNotEquals(firstAssigned.currentContentId(), secondAssigned.currentContentId());
        assertEquals(withCurrentContent(firstBefore, firstAssigned.currentContentId()), firstAssigned);
        assertEquals(withCurrentContent(secondBefore, secondAssigned.currentContentId()), secondAssigned);
        assertEquals(firstAssigned.sizeBytes(), secondAssigned.sizeBytes());
        assertEquals(firstAssigned.modifiedTimeEpochSecond(), secondAssigned.modifiedTimeEpochSecond());
        assertEquals(firstAssigned.modifiedTimeNano(), secondAssigned.modifiedTimeNano());

        ContentRecord firstContent = catalogRepository.findContentRecordById(firstAssigned.currentContentId())
                .orElseThrow();
        ContentRecord secondContent = catalogRepository.findContentRecordById(secondAssigned.currentContentId())
                .orElseThrow();
        assertEquals(firstAssigned.sizeBytes(), firstContent.sizeBytes());
        assertEquals(secondAssigned.sizeBytes(), secondContent.sizeBytes());
        assertTrue(firstContent.createdAtMs() > 0);
        assertTrue(secondContent.createdAtMs() > 0);

        assertEquals(assignedBefore, requireFile(selectedSource, "assigned.dat"));
        assertEquals(missing, requireFile(selectedSource, "missing.dat"));
        assertEquals(stale, requireFile(selectedSource, "stale.dat"));
        assertEquals(outside, requireFile(outsideSource, "outside.dat"));
        assertEquals(contentCountBefore + 2, countRows("content_record"));
        assertEquals(completedExecution, durableExecution(scanRun.scanRun().id(), job.id()));
        assertEquals(1, countRows("job"));
        assertEquals(2, countRows("job_stage"));
        assertEquals(0, countRows("analysis_record"));
        assertEquals(0, countRows("content_hash"));

        mockMvc.perform(post(assignmentPath(scanRun.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0));
        assertEquals(contentCountBefore + 2, countRows("content_record"));
        assertEquals(completedExecution, durableExecution(scanRun.scanRun().id(), job.id()));
    }

    @Test
    void assignsEveryCandidateAcrossBoundedIdPages() throws Exception {
        CompletedScan completedScan = createCompletedScan(
                Files.createDirectory(temporaryDirectory.resolve("paged")));
        ScanRunSource source = completedScan.sources().getFirst();
        for (int index = 0; index < PAGE_SIZE + 1; index++) {
            catalogRepository.insert(new FileEntry(
                    null, source.sourceId(), "file-" + index, "file-" + index, null, "PRESENT", index,
                    null, null, 0, 1, 1, source.id(), source.completedGeneration()));
        }

        List<ContentAssignmentCandidate> firstPage = catalogRepository.findContentAssignmentCandidates(
                source.id(), source.completedGeneration(), 0, PAGE_SIZE);
        assertEquals(PAGE_SIZE, firstPage.size());
        for (int index = 1; index < firstPage.size(); index++) {
            assertTrue(firstPage.get(index - 1).fileEntryId() < firstPage.get(index).fileEntryId());
        }

        mockMvc.perform(post(assignmentPath(completedScan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(PAGE_SIZE + 1))
                .andExpect(jsonPath("$.skippedCount").value(0));

        assertEquals(PAGE_SIZE + 1, countRows("content_record"));
        assertEquals(PAGE_SIZE + 1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_entry WHERE current_content_id IS NOT NULL", Long.class));
    }

    @Test
    void unknownScanRunAndMissingExecutionReturnNotFound() throws Exception {
        mockMvc.perform(post(assignmentPath(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());

        Source source = insertSource("No execution", temporaryDirectory.resolve("unavailable"));
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        mockMvc.perform(post(assignmentPath(scanRun.scanRun().id())))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "rejects incomplete state: {0}")
    @MethodSource("ineligibleMutations")
    void rejectsIncompleteLifecycleWithoutMutation(String description, Consumer<CompletedScan> mutation)
            throws Exception {
        CompletedScan completedScan = createCompletedScan(
                Files.createDirectory(temporaryDirectory.resolve("ineligible")));
        mutation.accept(completedScan);
        DurableState beforeRequest = durableState();

        mockMvc.perform(post(assignmentPath(completedScan.scanRun().id())))
                .andExpect(status().isConflict());

        assertEquals(beforeRequest, durableState());
    }

    static Stream<Object[]> ineligibleMutations() {
        return Stream.of(
                mutation("ScanRun is not COMPLETED", scan -> scan.update(
                        "UPDATE scan_run SET status = 'RUNNING' WHERE id = ?", scan.scanRun().id())),
                mutation("ScanRun start time is null", scan -> scan.update(
                        "UPDATE scan_run SET started_at_ms = NULL WHERE id = ?", scan.scanRun().id())),
                mutation("ScanRun finish time is null", scan -> scan.update(
                        "UPDATE scan_run SET finished_at_ms = NULL WHERE id = ?", scan.scanRun().id())),
                mutation("Job is not COMPLETED", scan -> scan.update(
                        "UPDATE job SET status = 'RUNNING' WHERE id = ?", scan.job().id())),
                mutation("Job current stage is populated", scan -> scan.update(
                        "UPDATE job SET current_stage_type = 'RECONCILIATION' WHERE id = ?", scan.job().id())),
                mutation("Job finish time is null", scan -> scan.update(
                        "UPDATE job SET finished_at_ms = NULL WHERE id = ?", scan.job().id())),
                mutation("DISCOVERY is not COMPLETED", scan -> scan.update(
                        "UPDATE job_stage SET status = 'RUNNING' WHERE id = ?", scan.discoveryStage().id())),
                mutation("DISCOVERY does not exist", scan -> scan.update(
                        "DELETE FROM job_stage WHERE id = ?", scan.discoveryStage().id())),
                mutation("RECONCILIATION is not COMPLETED", scan -> scan.update(
                        "UPDATE job_stage SET status = 'RUNNING' WHERE id = ?", scan.reconciliationStage().id())),
                mutation("RECONCILIATION does not exist", scan -> scan.update(
                        "DELETE FROM job_stage WHERE id = ?", scan.reconciliationStage().id())),
                mutation("Source is not COMPLETED", scan -> scan.update(
                        "UPDATE scan_run_source SET status = 'DISCOVERED' WHERE id = ?",
                        scan.sources().getFirst().id())),
                mutation("traversal generation is not positive", scan -> scan.update(
                        "UPDATE scan_run_source SET completed_generation = NULL, traversal_generation = 0 WHERE id = ?",
                        scan.sources().getFirst().id())),
                mutation("completed generation is null", scan -> scan.update(
                        "UPDATE scan_run_source SET completed_generation = NULL WHERE id = ?",
                        scan.sources().getFirst().id())),
                mutation("completed generation differs", scan -> scan.update(
                        "UPDATE scan_run_source SET traversal_generation = traversal_generation + 1 WHERE id = ?",
                        scan.sources().getFirst().id())),
                mutation("Source completion time is null", scan -> scan.update(
                        "UPDATE scan_run_source SET completed_at_ms = NULL WHERE id = ?",
                        scan.sources().getFirst().id())));
    }

    @ParameterizedTest(name = "rolls back stale publication: {0}")
    @MethodSource("staleMutations")
    void guardedPublicationRejectsStaleCandidatesWithoutOrphanContent(
            String description, String mutation) throws Exception {
        CompletedScan completedScan = createCompletedScan(
                Files.createDirectory(temporaryDirectory.resolve("stale")));
        ScanRunSource source = completedScan.sources().getFirst();
        FileEntry fileEntry = catalogRepository.insert(new FileEntry(
                null, source.sourceId(), "candidate.dat", "candidate.dat", null, "PRESENT", 10,
                100L, 200, 5, 10, 20, source.id(), source.completedGeneration()));
        ContentAssignmentCandidate candidate = catalogRepository.findContentAssignmentCandidates(
                source.id(), source.completedGeneration(), 0, PAGE_SIZE).getFirst();

        switch (mutation) {
            case "revision" -> jdbcTemplate.update(
                    "UPDATE file_entry SET observation_revision = observation_revision + 1 WHERE id = ?",
                    fileEntry.id());
            case "size" -> jdbcTemplate.update(
                    "UPDATE file_entry SET size_bytes = size_bytes + 1 WHERE id = ?", fileEntry.id());
            case "missing" -> jdbcTemplate.update(
                    "UPDATE file_entry SET presence_status = 'MISSING' WHERE id = ?", fileEntry.id());
            case "content" -> {
                ContentRecord otherContent = catalogRepository.insert(new ContentRecord(null, 10, 1));
                jdbcTemplate.update("UPDATE file_entry SET current_content_id = ? WHERE id = ?",
                        otherContent.id(), fileEntry.id());
            }
            default -> throw new IllegalArgumentException("Unknown mutation " + mutation);
        }
        FileEntry changedEntry = catalogRepository.findFileEntryById(fileEntry.id()).orElseThrow();
        long contentCountBefore = countRows("content_record");

        assertThrows(StaleContentAssignmentException.class,
                () -> contentAssignmentWriter.assign(candidate, System.currentTimeMillis()));

        assertEquals(changedEntry, catalogRepository.findFileEntryById(fileEntry.id()).orElseThrow());
        assertEquals(contentCountBefore, countRows("content_record"));
    }

    static Stream<Object[]> staleMutations() {
        return Stream.of(
                new Object[] { "observation revision changed", "revision" },
                new Object[] { "size changed", "size" },
                new Object[] { "entry became MISSING", "missing" },
                new Object[] { "another content ID was attached", "content" });
    }

    @Test
    void publicationAllowsLaterUnchangedTraversalIdentity() throws Exception {
        Source source = insertSource("Later observation", Files.createDirectory(
                temporaryDirectory.resolve("later-observation")));
        CompletedScan firstScan = createCompletedScan(source);
        ScanRunSource firstScanSource = firstScan.sources().getFirst();
        FileEntry fileEntry = catalogRepository.insert(new FileEntry(
                null, source.id(), "candidate.dat", "candidate.dat", null, "PRESENT", 10,
                100L, 200, 5, 10, 20, firstScanSource.id(), firstScanSource.completedGeneration()));
        ContentAssignmentCandidate candidate = catalogRepository.findContentAssignmentCandidates(
                firstScanSource.id(), firstScanSource.completedGeneration(), 0, PAGE_SIZE).getFirst();
        ScanRunDetails laterScan = scanRunService.create(List.of(source.id()));
        ScanRunSource laterScanSource = laterScan.sources().getFirst();
        jdbcTemplate.update("""
                UPDATE file_entry
                SET last_seen_scan_run_source_id = ?, last_seen_traversal_generation = 1
                WHERE id = ?
                """, laterScanSource.id(), fileEntry.id());

        contentAssignmentWriter.assign(candidate, System.currentTimeMillis());

        assertNotNull(catalogRepository.findFileEntryById(fileEntry.id()).orElseThrow().currentContentId());
        assertEquals(1, countRows("content_record"));
    }

    @Test
    void unchangedLaterScanPreservesAssignedContentIdentity() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("repeat-scan"));
        Files.writeString(root.resolve("same.dat"), "unchanged");
        Source source = insertSource("Repeat scan", root);
        CompletedScan firstScan = createCompletedScan(source);

        mockMvc.perform(post(assignmentPath(firstScan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1));
        Long assignedContentId = requireFile(source, "same.dat").currentContentId();
        assertNotNull(assignedContentId);

        CompletedScan secondScan = createCompletedScan(source);
        FileEntry reobserved = requireFile(source, "same.dat");
        assertEquals(assignedContentId, reobserved.currentContentId());

        mockMvc.perform(post(assignmentPath(secondScan.scanRun().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0));
        assertEquals(assignedContentId, requireFile(source, "same.dat").currentContentId());
        assertEquals(1, countRows("content_record"));
    }

    @Test
    void writerUsesARealPerCandidateTransactionBoundary() throws Exception {
        assertTrue(AopUtils.isAopProxy(contentAssignmentWriter));
        assertNotNull(ContentAssignmentWriter.class
                .getMethod("assign", ContentAssignmentCandidate.class, long.class)
                .getAnnotation(Transactional.class));
        assertNull(ContentAssignmentService.class
                .getMethod("assign", long.class)
                .getAnnotation(Transactional.class));
    }

    private CompletedScan createCompletedScan(Path root) {
        return createCompletedScan(insertSource("Source", root));
    }

    private CompletedScan createCompletedScan(Source source) {
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        Job job = scanExecutionService.create(scanRun.scanRun().id()).job();
        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        reconciliationService.execute(scanRun.scanRun().id());
        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        return new CompletedScan(
                jdbcTemplate,
                scanRepository.findScanRunById(scanRun.scanRun().id()).orElseThrow(),
                scanRepository.findScanRunSourcesByScanRunId(scanRun.scanRun().id()),
                jobRepository.findJobById(job.id()).orElseThrow(),
                stages.get(0),
                stages.get(1));
    }

    private Source insertSource(String name, Path root) {
        String rootPath = root.toAbsolutePath().toString();
        return catalogRepository.insert(new Source(null, name, rootPath, rootPath, 0, 1, 1));
    }

    private FileEntry requireFile(Source source, String pathKey) {
        return catalogRepository.findFileEntryBySourceIdAndPathKey(source.id(), pathKey).orElseThrow();
    }

    private FileEntry withCurrentContent(FileEntry entry, long contentId) {
        return new FileEntry(
                entry.id(), entry.sourceId(), entry.relativePath(), entry.pathKey(), contentId,
                entry.presenceStatus(), entry.sizeBytes(), entry.modifiedTimeEpochSecond(),
                entry.modifiedTimeNano(), entry.observationRevision(), entry.firstSeenAtMs(),
                entry.lastSeenAtMs(), entry.lastSeenScanRunSourceId(), entry.lastSeenTraversalGeneration());
    }

    private DurableExecution durableExecution(long scanRunId, long jobId) {
        return new DurableExecution(
                scanRepository.findScanRunById(scanRunId).orElseThrow(),
                scanRepository.findScanRunSourcesByScanRunId(scanRunId),
                jobRepository.findJobById(jobId).orElseThrow(),
                jobRepository.findJobStagesByJobId(jobId));
    }

    private DurableState durableState() {
        return new DurableState(
                jdbcTemplate.queryForList("SELECT * FROM scan_run ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM scan_run_source ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM job ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM job_stage ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM file_entry ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM content_record ORDER BY id"));
    }

    private long countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    private String assignmentPath(long scanRunId) {
        return "/api/scan-runs/" + scanRunId + "/content-assignment";
    }

    private static Object[] mutation(String description, Consumer<CompletedScan> mutation) {
        return new Object[] { description, mutation };
    }

    private record DurableExecution(
            ScanRun scanRun,
            List<ScanRunSource> sources,
            Job job,
            List<JobStage> stages) {
    }

    private record DurableState(
            List<Map<String, Object>> scanRuns,
            List<Map<String, Object>> scanRunSources,
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> stages,
            List<Map<String, Object>> fileEntries,
            List<Map<String, Object>> contentRecords) {
    }

    private record CompletedScan(
            JdbcTemplate jdbcTemplate,
            ScanRun scanRun,
            List<ScanRunSource> sources,
            Job job,
            JobStage discoveryStage,
            JobStage reconciliationStage) {

        void update(String sql, long id) {
            jdbcTemplate.update(sql, id);
        }
    }
}
