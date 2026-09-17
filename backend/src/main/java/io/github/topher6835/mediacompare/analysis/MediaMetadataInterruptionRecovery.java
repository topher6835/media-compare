package io.github.topher6835.mediacompare.analysis;

import java.util.List;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaMetadataInterruptionRecovery {

    public static final String RESTART_MESSAGE = "Execution interrupted by application restart";
    public static final String ANALYSIS_RESTART_MESSAGE =
            "Analysis interrupted by application restart";

    private final JobRepository jobs;
    private final AnalysisRepository analyses;

    public MediaMetadataInterruptionRecovery(JobRepository jobs, AnalysisRepository analyses) {
        this.jobs = jobs;
        this.analyses = analyses;
    }

    @Transactional
    public void recoverAtStartup() {
        long now = System.currentTimeMillis();
        for (Job job : jobs.findActiveMediaMetadataJobs()) {
            failActive(job, now, RESTART_MESSAGE);
        }
        analyses.recoverInterrupted(
                ImageIoMediaMetadataDefinition.definition(), now, ANALYSIS_RESTART_MESSAGE);
    }

    @Transactional
    public void failIfActive(long jobId, String message) {
        Job job = jobs.findJobById(jobId).orElseThrow();
        if (!MediaMetadataJobDefinition.JOB_TYPE.equals(job.jobType())
                || job.executionVersion() != MediaMetadataJobDefinition.EXECUTION_VERSION
                || List.of("COMPLETED", "FAILED").contains(job.status())) {
            return;
        }
        failActive(job, System.currentTimeMillis(), message);
    }

    private void failActive(Job job, long failedAtMs, String message) {
        if (job.scanRunId() != null
                || !List.of("PENDING", "RUNNING").contains(job.status())
                || !MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(job.currentStageType())) {
            throw new IllegalStateException("Invalid active media metadata Job " + job.id());
        }
        List<JobStage> stages = jobs.findJobStagesByJobId(job.id());
        if (stages.size() != 1
                || !MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(stages.getFirst().stageType())
                || !List.of("PENDING", "RUNNING").contains(stages.getFirst().status())) {
            throw new IllegalStateException("Invalid active media metadata stage for Job " + job.id());
        }
        if (jobs.failActiveImageMetadataStage(
                job.id(), stages.getFirst().id(), failedAtMs, message) != 1
                || jobs.failActiveMediaMetadataJob(job.id(), failedAtMs, message) != 1) {
            throw new IllegalStateException("Could not recover media metadata Job " + job.id());
        }
    }
}
