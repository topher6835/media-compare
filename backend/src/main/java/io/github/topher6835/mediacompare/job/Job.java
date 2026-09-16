package io.github.topher6835.mediacompare.job;

public record Job(
        Long id,
        Long scanRunId,
        String jobType,
        long executionVersion,
        String status,
        String currentStageType,
        long progressCompleted,
        Long progressTotal,
        long attemptCount,
        long createdAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String errorMessage) {
}
