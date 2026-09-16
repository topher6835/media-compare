package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReconciliationExecutionState {

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;

    public ReconciliationExecutionState(ScanRepository scanRepository, JobRepository jobRepository) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void start(Job job, JobStage reconciliationStage, long sourceCount, long startedAtMs) {
        requireRows(jobRepository.startReconciliation(
                job.id(), reconciliationStage.id(), job.executionVersion(), sourceCount, startedAtMs), 2,
                "start RECONCILIATION");
    }

    @Transactional
    public void complete(long scanRunId, Job job, JobStage reconciliationStage,
            long sourceCount, long completedAtMs) {
        requireOne(jobRepository.completeReconciliationStage(
                reconciliationStage.id(), job.executionVersion(), sourceCount, completedAtMs),
                "complete RECONCILIATION stage");
        if (job.executionVersion() == ScanExecutionDefinition.VERSION_1) {
            requireOne(jobRepository.completeVersion1Job(job.id(), sourceCount, completedAtMs),
                    "complete Job");
            requireOne(scanRepository.completeScanRun(scanRunId, completedAtMs),
                    "complete ScanRun");
            return;
        }

        jobRepository.insert(new JobStage(
                null,
                job.id(),
                ScanExecutionDefinition.CONTENT_ASSIGNMENT,
                null,
                "PENDING",
                0,
                null,
                0,
                completedAtMs,
                null,
                null,
                null));
        requireOne(jobRepository.advanceVersion2Job(
                job.id(), ScanExecutionDefinition.RECONCILIATION,
                ScanExecutionDefinition.CONTENT_ASSIGNMENT, sourceCount),
                "advance Job to CONTENT_ASSIGNMENT");
    }

    private static void requireOne(int rows, String action) {
        requireRows(rows, 1, action);
    }

    private static void requireRows(int actualRows, int expectedRows, String action) {
        if (actualRows != expectedRows) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
