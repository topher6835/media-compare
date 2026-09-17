package io.github.topher6835.mediacompare.web;

import java.util.List;
import io.github.topher6835.mediacompare.scan.IndexingRunDetails;

public record IndexingRunResponse(long scanRunId, long jobId, String requestKey, String status, String currentStage,
        long progressCompleted, Long progressTotal, long createdAtMs, Long startedAtMs, Long finishedAtMs,
        String errorMessage, List<Long> sourceIds, List<Stage> stages, AssignmentResult assignmentResult,
        HashingResult hashingResult, boolean completedWithIssues) {

    public static IndexingRunResponse from(IndexingRunDetails run) {
        var job = run.job();
        var assignment = run.assignmentResult();
        var hashing = run.hashingResult();
        return new IndexingRunResponse(run.scanRun().id(), job.id(), run.scanRun().requestKey(), job.status(),
                job.currentStageType(), job.progressCompleted(), job.progressTotal(), run.scanRun().createdAtMs(),
                job.startedAtMs(), job.finishedAtMs(), job.errorMessage(), run.sourceIds(),
                run.stages().stream().map(stage -> new Stage(stage.stageType(), stage.status(), stage.progressCompleted(),
                        stage.progressTotal(), stage.startedAtMs(), stage.finishedAtMs(), stage.errorMessage())).toList(),
                assignment == null ? null : new AssignmentResult(assignment.assignedCount(), assignment.skippedCount()),
                hashing == null ? null : new HashingResult(hashing.hashedCount(), hashing.cachedCount(), hashing.skippedCount(), hashing.failedCount()),
                run.completedWithIssues());
    }

    public record Stage(String stageType, String status, long progressCompleted, Long progressTotal,
            Long startedAtMs, Long finishedAtMs, String errorMessage) {}
    public record AssignmentResult(long assignedCount, long skippedCount) {}
    public record HashingResult(long hashedCount, long cachedCount, long skippedCount, long failedCount) {}
}
