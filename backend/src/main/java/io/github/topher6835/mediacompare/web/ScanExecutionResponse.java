package io.github.topher6835.mediacompare.web;

import java.util.List;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.scan.ScanExecutionDetails;

public record ScanExecutionResponse(
        long jobId,
        long scanRunId,
        String jobType,
        String status,
        String currentStageType,
        long progressCompleted,
        Long progressTotal,
        long attemptCount,
        long createdAtMs,
        List<JobStageResponse> stages) {

    public static ScanExecutionResponse from(ScanExecutionDetails details) {
        Job job = details.job();
        return new ScanExecutionResponse(
                job.id(),
                job.scanRunId(),
                job.jobType(),
                job.status(),
                job.currentStageType(),
                job.progressCompleted(),
                job.progressTotal(),
                job.attemptCount(),
                job.createdAtMs(),
                details.stages().stream()
                        .map(JobStageResponse::from)
                        .toList());
    }
}
