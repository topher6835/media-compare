package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResult;
import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResultCodec;
import io.github.topher6835.mediacompare.analysis.MediaMetadataExecutionDetails;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobStage;

public record MediaMetadataRunResponse(
        long jobId,
        String status,
        long createdAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String errorMessage,
        Stage stage) {

    public static MediaMetadataRunResponse from(
            MediaMetadataExecutionDetails details, ImageMetadataStageResultCodec codec) {
        Job job = details.job();
        JobStage durableStage = details.stages().getFirst();
        Result result = null;
        if ("COMPLETED".equals(durableStage.status())) {
            ImageMetadataStageResult stored = codec.read(durableStage.resultJson());
            result = new Result(stored.completedAvailable(), stored.completedUnsupported(),
                    stored.failed(), stored.staleOrUnavailable(),
                    stored.failed() > 0 || stored.staleOrUnavailable() > 0);
        }
        return new MediaMetadataRunResponse(job.id(), job.status(), job.createdAtMs(),
                job.startedAtMs(), job.finishedAtMs(), job.errorMessage(),
                new Stage(durableStage.status(), durableStage.progressCompleted(),
                        durableStage.startedAtMs(), durableStage.finishedAtMs(),
                        durableStage.errorMessage(), result));
    }

    public record Stage(String status, long candidatesAttempted, Long startedAtMs,
            Long finishedAtMs, String errorMessage, Result result) {}

    public record Result(long completedAvailable, long completedUnsupported, long failed,
            long staleOrUnavailable, boolean completedWithIssues) {}
}
