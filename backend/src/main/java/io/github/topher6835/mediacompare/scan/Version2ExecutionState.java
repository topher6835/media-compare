package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Version2ExecutionState {

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;

    public Version2ExecutionState(ScanRepository scanRepository, JobRepository jobRepository) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void startStage(Job job, JobStage stage, long startedAtMs) {
        requireOne(jobRepository.claimVersion2Stage(
                job.id(), stage.id(), stage.stageType(), startedAtMs), "claim " + stage.stageType());
        requireOne(jobRepository.resetVersion2JobProgress(job.id(), stage.stageType()),
                "reset Job progress for " + stage.stageType());
    }

    @Transactional
    public void completeAssignment(Job job, JobStage stage, String resultJson,
            long progressCompleted, long completedAtMs) {
        requireOne(jobRepository.completeVersion2Stage(
                job.id(), stage.id(), ScanExecutionDefinition.CONTENT_ASSIGNMENT,
                resultJson, progressCompleted, completedAtMs), "complete CONTENT_ASSIGNMENT");
        jobRepository.insert(new JobStage(
                null,
                job.id(),
                ScanExecutionDefinition.CONTENT_HASHING,
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
                job.id(), ScanExecutionDefinition.CONTENT_ASSIGNMENT,
                ScanExecutionDefinition.CONTENT_HASHING, progressCompleted),
                "advance Job to CONTENT_HASHING");
    }

    @Transactional
    public void completeHashing(long scanRunId, Job job, JobStage stage, String resultJson,
            long progressCompleted, long completedAtMs) {
        requireOne(jobRepository.completeVersion2Stage(
                job.id(), stage.id(), ScanExecutionDefinition.CONTENT_HASHING,
                resultJson, progressCompleted, completedAtMs), "complete CONTENT_HASHING");
        requireOne(jobRepository.completeVersion2Job(job.id(), progressCompleted, completedAtMs),
                "complete Job");
        requireOne(scanRepository.completeScanRun(scanRunId, completedAtMs), "complete ScanRun");
    }

    @Transactional
    public void failCurrentStage(long scanRunId, Job job, JobStage stage,
            long failedAtMs, String errorMessage) {
        requireOne(jobRepository.failVersion2Stage(
                job.id(), stage.id(), stage.stageType(), failedAtMs, errorMessage),
                "fail " + stage.stageType());
        requireOne(jobRepository.failVersion2Job(
                job.id(), stage.stageType(), failedAtMs, errorMessage), "fail Job");
        requireOne(scanRepository.failScanRun(scanRunId, failedAtMs, errorMessage), "fail ScanRun");
    }

    private static void requireOne(int rows, String action) {
        if (rows != 1) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
