package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.analysis.ContentHashWriter;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.job.*;
import io.github.topher6835.mediacompare.scan.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Version2RecoveryTests {
    @TempDir Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired ScanRepository scans;
    @Autowired JobRepository jobs;
    @Autowired ScanRunService requests;
    @Autowired Version2ScanExecutionService executions;
    @Autowired ScanExecutionService discovery;
    @Autowired ReconciliationService reconciliation;
    @Autowired ReconciliationExecutionState reconciliationState;
    @Autowired ReconciliationWriter reconciliationWriter;
    @Autowired Version2ContentAssignmentService assignment;
    @Autowired ContentAssignmentWriter assignmentWriter;
    @Autowired Version2ExecutionState state;
    @Autowired Version2InterruptionRecovery recovery;
    @Autowired Version2IndexingStartup startup;
    @Autowired ContentHashFileHasher hasher;
    @Autowired ContentHashWriter hashWriter;

    @BeforeEach
    void clear() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_recovery");
        for (String table : List.of("content_hash", "job_stage", "file_entry", "scan_run_source",
                "working_set_content", "analysis_record", "job", "scan_run", "working_set", "content_record", "source")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void pendingRecoveryLeavesUnclaimedDiscoveryAndReleasesAdmission() throws Exception {
        Job job = pending("pending");
        JobStage before = stages(job).getFirst();
        startup.afterPropertiesSet();
        assertFailed(job);
        assertEquals(before, stages(job).getFirst());
        assertEquals("PENDING", sources(job).getFirst().status());
        assertEquals("PENDING", pending("next").status());
    }

    @Test
    void runningDiscoveryRetainsObservationsWithoutReconciliation() throws Exception {
        Job job = pending("discovery");
        long now = System.currentTimeMillis();
        scans.startScanRun(job.scanRunId(), now);
        jobs.startJob(job.id(), 2, now);
        jobs.startDiscoveryStage(stages(job).getFirst().id(), 2, now);
        ScanRunSource source = sources(job).getFirst();
        scans.startSourceDiscovery(source.id(), 1, now);
        catalog.observeFile(new FileObservation(source.sourceId(), "committed.txt", "committed.txt",
                4, 1L, 0, now, source.id(), 1));
        var observations = jdbc.queryForList("SELECT * FROM file_entry");

        startup.afterPropertiesSet();

        assertFailed(job);
        assertEquals(List.of("FAILED"), stages(job).stream().map(JobStage::status).toList());
        assertEquals("FAILED", sources(job).getFirst().status());
        assertNull(sources(job).getFirst().completedGeneration());
        assertEquals(observations, jdbc.queryForList("SELECT * FROM file_entry"));
    }

    @Test
    void discoveredSourcesStayDiscoveredAndPendingReconciliationNeverRuns() throws Exception {
        Job job = pending("discovered");
        discovery.executeVersion2Discovery(job.scanRunId());
        var before = sources(job);
        startup.afterPropertiesSet();
        assertFailed(job);
        assertEquals(List.of("COMPLETED", "PENDING"), stages(job).stream().map(JobStage::status).toList());
        assertEquals(before, sources(job));
    }

    @Test
    void reconciliationPreservesCommittedSourcesAndDoesNotSweepUnfinishedOnes() throws Exception {
        Source first = source("first");
        Source second = source("second");
        for (Source source : List.of(first, second)) {
            jdbc.update("""
                    INSERT INTO file_entry (source_id, relative_path, path_key, presence_status, size_bytes,
                        observation_revision, first_seen_at_ms, last_seen_at_ms)
                    VALUES (?, 'old.txt', 'old.txt', 'PRESENT', 1, 0, 1, 1)
                    """, source.id());
        }
        long scanId = requests.create(List.of(first.id(), second.id())).scanRun().id();
        Job job = executions.create(scanId).job();
        discovery.executeVersion2Discovery(scanId);
        job = jobs.findJobById(job.id()).orElseThrow();
        JobStage stage = stages(job).get(1);
        reconciliationState.start(job, stage, 2, System.currentTimeMillis());
        reconciliationWriter.reconcile(job.id(), stage.id(), 2, sources(job).getFirst(), 1, System.currentTimeMillis());
        var before = sources(job);
        var files = jdbc.queryForList("SELECT * FROM file_entry");

        startup.afterPropertiesSet();

        assertFailed(job);
        assertEquals(List.of("COMPLETED", "FAILED"), stages(job).stream().map(JobStage::status).toList());
        assertEquals(before, sources(job));
        assertEquals(List.of("COMPLETED", "DISCOVERED"), sources(job).stream().map(ScanRunSource::status).toList());
        assertEquals(files, jdbc.queryForList("SELECT * FROM file_entry"));
        assertEquals("MISSING", jdbc.queryForObject(
                "SELECT presence_status FROM file_entry WHERE source_id = ? AND path_key = 'old.txt'", String.class, first.id()));
        assertEquals("PRESENT", jdbc.queryForObject(
                "SELECT presence_status FROM file_entry WHERE source_id = ? AND path_key = 'old.txt'", String.class, second.id()));
    }

    @Test
    void assignmentRecoveryRetainsPublishedAssociationsAndNeverStartsHashing() throws Exception {
        Job job = throughReconciliation("assignment");
        state.startStage(job, stages(job).get(2), System.currentTimeMillis());
        ScanRunSource source = sources(job).getFirst();
        var candidate = catalog.findContentAssignmentCandidates(source.id(), source.completedGeneration(), 0, 250).getFirst();
        assignmentWriter.assign(candidate, System.currentTimeMillis());
        var files = jdbc.queryForList("SELECT * FROM file_entry");
        var records = jdbc.queryForList("SELECT * FROM content_record");
        startup.afterPropertiesSet();
        assertFailed(job);
        assertEquals(List.of("COMPLETED", "COMPLETED", "FAILED"), stages(job).stream().map(JobStage::status).toList());
        assertEquals(files, jdbc.queryForList("SELECT * FROM file_entry"));
        assertEquals(records, jdbc.queryForList("SELECT * FROM content_record"));
        assertFalse(records.isEmpty());
    }

    @Test
    void hashingRecoveryRetainsArtifactsAndEarlierResultWithoutInventingFinalCounts() throws Exception {
        Job job = throughReconciliation("hashing");
        assignment.execute(job.scanRunId());
        job = jobs.findJobById(job.id()).orElseThrow();
        JobStage assignmentBefore = stages(job).get(2);
        state.startStage(job, stages(job).get(3), System.currentTimeMillis());
        ScanRunSource source = sources(job).getFirst();
        var candidate = catalog.findContentHashCandidates(source.id(), source.completedGeneration(), 0, 250).getFirst();
        Path root = Path.of(catalog.findSourceById(source.sourceId()).orElseThrow().rootPath());
        hashWriter.publish(candidate, hasher.hash(root, candidate), 1, System.currentTimeMillis());
        var hashes = jdbc.queryForList("SELECT * FROM content_hash");
        var analyses = jdbc.queryForList("SELECT * FROM analysis_record");

        startup.afterPropertiesSet();

        assertFailed(job);
        assertEquals(assignmentBefore, stages(job).get(2));
        assertEquals("FAILED", stages(job).get(3).status());
        assertNull(stages(job).get(3).resultJson());
        assertEquals(hashes, jdbc.queryForList("SELECT * FROM content_hash"));
        assertEquals(analyses, jdbc.queryForList("SELECT * FROM analysis_record"));
        assertFalse(hashes.isEmpty());
    }

    @Test
    void terminalAndVersion1ExecutionsAreUntouchedAndRecoveryIsRepeatable() throws Exception {
        Job completed = pending("completed");
        executions.run(completed.scanRunId());
        Job failed = pending("failed");
        recovery.failIfActive(failed.id(), "previous failure");
        long legacyScan = requests.create(List.of(source("legacy").id())).scanRun().id();
        discovery.create(legacyScan);
        var before = jdbc.queryForList("SELECT * FROM job ORDER BY id");
        var beforeScans = jdbc.queryForList("SELECT * FROM scan_run ORDER BY id");
        var beforeStages = jdbc.queryForList("SELECT * FROM job_stage ORDER BY id");
        startup.afterPropertiesSet();
        startup.afterPropertiesSet();
        assertEquals(before, jdbc.queryForList("SELECT * FROM job ORDER BY id"));
        assertEquals(beforeScans, jdbc.queryForList("SELECT * FROM scan_run ORDER BY id"));
        assertEquals(beforeStages, jdbc.queryForList("SELECT * FROM job_stage ORDER BY id"));
    }

    @Test
    void impossibleStateFailsRecoveryWithoutRepair() throws Exception {
        Job job = pending("malformed");
        jdbc.update("UPDATE job SET current_stage_type = NULL WHERE id = ?", job.id());
        var before = jdbc.queryForList("SELECT * FROM job");
        var failure = assertThrows(IllegalStateException.class, startup::afterPropertiesSet);
        assertTrue(failure.getMessage().contains("Job " + job.id()));
        assertEquals(before, jdbc.queryForList("SELECT * FROM job"));
        assertEquals("PENDING", scans.findScanRunById(job.scanRunId()).orElseThrow().status());
    }

    @Test
    void recoveryTransactionRollsBackAllFinalizationIfJobUpdateFails() throws Exception {
        Job job = throughReconciliation("rollback");
        state.startStage(job, stages(job).get(2), System.currentTimeMillis());
        jdbc.execute("""
                CREATE TRIGGER reject_recovery BEFORE UPDATE OF status ON job
                WHEN NEW.status = 'FAILED' BEGIN SELECT RAISE(ABORT, 'fixture'); END
                """);
        try {
            assertThrows(IllegalStateException.class, startup::afterPropertiesSet);
            assertEquals("RUNNING", jobs.findJobById(job.id()).orElseThrow().status());
            assertEquals("RUNNING", scans.findScanRunById(job.scanRunId()).orElseThrow().status());
            assertEquals("RUNNING", stages(job).get(2).status());
        } finally {
            jdbc.execute("DROP TRIGGER reject_recovery");
        }
    }

    private Source source(String name) throws Exception {
        Path root = Files.createDirectory(directory.resolve(name));
        Files.writeString(root.resolve("one.txt"), "content");
        return catalog.insert(new Source(null, name, root.toString(), root.toString(), 0, 1, 1));
    }

    private Job pending(String name) throws Exception {
        return executions.create(requests.create(List.of(source(name).id())).scanRun().id()).job();
    }

    private Job throughReconciliation(String name) throws Exception {
        Job job = pending(name);
        discovery.executeVersion2Discovery(job.scanRunId());
        reconciliation.executeVersion2(job.scanRunId());
        return jobs.findJobById(job.id()).orElseThrow();
    }

    private List<JobStage> stages(Job job) { return jobs.findJobStagesByJobId(job.id()); }
    private List<ScanRunSource> sources(Job job) { return scans.findScanRunSourcesByScanRunId(job.scanRunId()); }

    private void assertFailed(Job job) {
        Job actual = jobs.findJobById(job.id()).orElseThrow();
        ScanRun scan = scans.findScanRunById(job.scanRunId()).orElseThrow();
        assertEquals("FAILED", actual.status());
        assertEquals("FAILED", scan.status());
        assertNull(actual.currentStageType());
        assertNotNull(actual.finishedAtMs());
        assertEquals(actual.finishedAtMs(), scan.finishedAtMs());
        assertEquals(Version2InterruptionRecovery.RESTART_MESSAGE, actual.errorMessage());
        assertEquals(actual.errorMessage(), scan.errorMessage());
    }
}
