package io.github.topher6835.mediacompare.job;

public record JobStage(
        Long id,
        long jobId,
        String stageType,
        String status,
        long progressCompleted,
        Long progressTotal,
        long attemptCount,
        long createdAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String errorMessage) {
}
