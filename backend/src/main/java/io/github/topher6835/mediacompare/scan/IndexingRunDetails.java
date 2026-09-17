package io.github.topher6835.mediacompare.scan;

import java.util.List;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.analysis.ContentHashingStageResult;

public record IndexingRunDetails(ScanRun scanRun, Job job, List<Long> sourceIds, List<JobStage> stages,
        ContentAssignmentStageResult assignmentResult, ContentHashingStageResult hashingResult) {
    public boolean completedWithIssues() {
        return "COMPLETED".equals(job.status()) && hashingResult != null
                && (hashingResult.skippedCount() > 0 || hashingResult.failedCount() > 0);
    }
}
