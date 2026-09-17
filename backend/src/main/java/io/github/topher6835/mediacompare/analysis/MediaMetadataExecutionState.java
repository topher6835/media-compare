package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MediaMetadataExecutionState {

    private final JobRepository jobs;

    public MediaMetadataExecutionState(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public void start(Job job, JobStage stage, long startedAtMs) {
        requireOne(jobs.startMediaMetadataJob(job.id(), startedAtMs), "start media metadata Job");
        requireOne(jobs.startImageMetadataStage(job.id(), stage.id(), startedAtMs),
                "start IMAGE_METADATA stage");
    }

    @Transactional
    public void updateProgress(Job job, JobStage stage, long progressCompleted) {
        int rows = jobs.updateImageMetadataProgress(job.id(), stage.id(), progressCompleted);
        if (rows != 2) {
            throw new IllegalStateException("Could not update IMAGE_METADATA progress");
        }
    }

    @Transactional
    public void complete(
            Job job, JobStage stage, String resultJson, long progressCompleted, long completedAtMs) {
        requireOne(jobs.completeImageMetadataStage(
                job.id(), stage.id(), resultJson, progressCompleted, completedAtMs),
                "complete IMAGE_METADATA stage");
        requireOne(jobs.completeMediaMetadataJob(job.id(), progressCompleted, completedAtMs),
                "complete media metadata Job");
    }

    @Transactional
    public void fail(Job job, JobStage stage, long failedAtMs, String errorMessage) {
        requireOne(jobs.failActiveImageMetadataStage(
                job.id(), stage.id(), failedAtMs, errorMessage), "fail IMAGE_METADATA stage");
        requireOne(jobs.failActiveMediaMetadataJob(
                job.id(), failedAtMs, errorMessage), "fail media metadata Job");
    }

    private static void requireOne(int rows, String action) {
        if (rows != 1) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
