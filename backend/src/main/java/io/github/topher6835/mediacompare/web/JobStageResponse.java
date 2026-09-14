package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.job.JobStage;

public record JobStageResponse(
        String stageType,
        String status,
        long progressCompleted,
        Long progressTotal,
        long attemptCount) {

    public static JobStageResponse from(JobStage stage) {
        return new JobStageResponse(
                stage.stageType(),
                stage.status(),
                stage.progressCompleted(),
                stage.progressTotal(),
                stage.attemptCount());
    }
}
