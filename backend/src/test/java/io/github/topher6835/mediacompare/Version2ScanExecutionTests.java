package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.SourceMembership;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import io.github.topher6835.mediacompare.scan.Version2ContentAssignmentService;
import io.github.topher6835.mediacompare.analysis.Version2ContentHashingService;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.scan.IndexingRunReadService;
import io.github.topher6835.mediacompare.scan.ScanExecutionAlreadyExistsException;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.Version2ScanExecutionService;

/** Historical v1/v2 executions remain readable, while V6 writes only v3. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class Version2ScanExecutionTests extends V3ApiTestBase {
    @Autowired ScanRunService requests;
    @Autowired JobRepository jobs;
    @Autowired ScanExecutionService version1;
    @Autowired Version2ScanExecutionService version2;
    @Autowired IndexingRunReadService reads;
    @Autowired Version2ContentAssignmentService assignments;
    @Autowired SourceMembershipRepository memberships;
    @Autowired ScanRunService scanRuns;
    @Autowired Version2ContentHashingService hashing;

    @Test
    void historicalVersion2JobRemainsReadable() throws Exception {
        var source = source("historical-v2");
        long scanId = requests.create(List.of(source.id())).scanRun().id();
        long now = System.currentTimeMillis();
        Job job = jobs.insert(new Job(null, scanId, "SCAN", 2, "PENDING", "DISCOVERY",
                0, null, 0, now, null, null, null));
        jobs.insert(new JobStage(null, job.id(), "DISCOVERY", null, "PENDING",
                0, null, 0, now, null, null, null));

        assertEquals(job.id(), version2.findByScanRunId(scanId).orElseThrow().job().id());
        assertEquals(job.id(), reads.require(scanId).job().id());
        assertTrue(version1.findByScanRunId(scanId).isEmpty());
    }

    @Test
    void historicalWritersRejectNewWork() throws Exception {
        var source = source("read-only");
        long scanId = requests.create(List.of(source.id())).scanRun().id();
        assertThrows(UnsupportedOperationException.class, () -> version2.create(scanId));
        assertThrows(UnsupportedOperationException.class, () -> version2.run(scanId));
        assertThrows(ScanExecutionAlreadyExistsException.class, () -> version1.create(scanId));
        assertEquals(0, count("job"));
    }

    @Test
    void historicalV2AssignmentCannotMutateFileContentOrExecutionState() throws Exception {
        var source = source("historical-assignment");
        var scan = scanRuns.create(List.of(source.id()));
        long scanId = scan.scanRun().id();
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE scan_run SET status='RUNNING', started_at_ms=? WHERE id=?", now, scanId);
        var file = catalog.insert(new FileEntry(null, "UNRESOLVED", null, null, null, null,
                23, null, null, "txt", 0, now, now));
        memberships.insert(new SourceMembership(null, source.id(), file.id(), "legacy.txt", "legacy.txt",
                "ACTIVE", "PRESENT", 0, 0, now, now, null, null, null, null));
        Job job = jobs.insert(new Job(null, scanId, "SCAN", 2, "RUNNING", "CONTENT_ASSIGNMENT",
                2, null, 1, now, now, null, null));
        jobs.insert(new JobStage(null, job.id(), "DISCOVERY", null, "COMPLETED", 1, 1L, 1, now, now, now, null));
        jobs.insert(new JobStage(null, job.id(), "RECONCILIATION", null, "COMPLETED", 1, 1L, 1, now, now, now, null));
        jobs.insert(new JobStage(null, job.id(), "CONTENT_ASSIGNMENT", null, "PENDING", 0, null, 0, now, null, null, null));
        var jobBefore = jdbc.queryForMap("SELECT * FROM job WHERE id=?", job.id());
        var stagesBefore = jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? ORDER BY id", job.id());
        var fileBefore = jdbc.queryForMap("SELECT * FROM file_entry WHERE id=?", file.id());

        assertThrows(UnsupportedOperationException.class, () -> assignments.execute(scanId));

        assertEquals(0, count("content_record"));
        assertEquals(fileBefore, jdbc.queryForMap("SELECT * FROM file_entry WHERE id=?", file.id()));
        assertEquals(jobBefore, jdbc.queryForMap("SELECT * FROM job WHERE id=?", job.id()));
        assertEquals(stagesBefore, jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? ORDER BY id", job.id()));
    }

    @Test
    void historicalV2HashingCannotMutateAnalysisCatalogOrExecutionState() throws Exception {
        var source = source("historical-hashing");
        var scan = scanRuns.create(List.of(source.id()));
        long scanId = scan.scanRun().id();
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE scan_run SET status='RUNNING', started_at_ms=? WHERE id=?", now, scanId);
        var scanSource = scan.sources().getFirst();
        jdbc.update("""
                UPDATE scan_run_source SET status='COMPLETED', traversal_generation=1,
                    completed_generation=1, started_at_ms=?, completed_at_ms=?
                WHERE id=?
                """, now, now, scanSource.id());
        ContentRecord content = catalog.insert(new ContentRecord(null, 12, now));
        var location = LocationPathParser.parse(LocationDialect.UNIX,
                Path.of(source.rootPath()).resolve("legacy.bin").toString());
        String encodedPath = new LocationPathCodec().encode(location);
        String encodedKey = LocationKeyCodec.encode(location).value();
        var file = catalog.insert(new FileEntry(null, "RESOLVED", source.boundLocationContextId(),
                encodedPath, encodedKey, content.id(), 12, 10L, 20, "bin", 0, now, now));
        memberships.insert(new SourceMembership(null, source.id(), file.id(), "legacy.bin", "legacy.bin",
                "ACTIVE", "PRESENT", 0, 0, now, now, scanSource.id(), 1L, 1L, 1L));
        Job job = jobs.insert(new Job(null, scanId, "SCAN", 2, "RUNNING", "CONTENT_HASHING",
                3, null, 1, now, now, null, null));
        jobs.insert(new JobStage(null, job.id(), "DISCOVERY", null, "COMPLETED", 1, 1L, 1, now, now, now, null));
        jobs.insert(new JobStage(null, job.id(), "RECONCILIATION", null, "COMPLETED", 1, 1L, 1, now, now, now, null));
        jobs.insert(new JobStage(null, job.id(), "CONTENT_ASSIGNMENT", null, "COMPLETED", 1, 1L, 1, now, now, now, null));
        jobs.insert(new JobStage(null, job.id(), "CONTENT_HASHING", null, "PENDING", 0, null, 0, now, null, null, null));
        var scanBefore = jdbc.queryForMap("SELECT * FROM scan_run WHERE id=?", scanId);
        var jobBefore = jdbc.queryForMap("SELECT * FROM job WHERE id=?", job.id());
        var stagesBefore = jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? ORDER BY id", job.id());
        var fileBefore = jdbc.queryForMap("SELECT * FROM file_entry WHERE id=?", file.id());
        var contentBefore = jdbc.queryForMap("SELECT * FROM content_record WHERE id=?", content.id());

        assertThrows(UnsupportedOperationException.class, () -> hashing.execute(scanId));

        assertEquals(0, count("analysis_record"));
        assertEquals(0, count("content_hash"));
        assertEquals(fileBefore, jdbc.queryForMap("SELECT * FROM file_entry WHERE id=?", file.id()));
        assertEquals(contentBefore, jdbc.queryForMap("SELECT * FROM content_record WHERE id=?", content.id()));
        assertEquals(jobBefore, jdbc.queryForMap("SELECT * FROM job WHERE id=?", job.id()));
        assertEquals(stagesBefore, jdbc.queryForList("SELECT * FROM job_stage WHERE job_id=? ORDER BY id", job.id()));
        assertEquals(scanBefore, jdbc.queryForMap("SELECT * FROM scan_run WHERE id=?", scanId));
    }
}
