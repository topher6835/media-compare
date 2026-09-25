package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;

@Service
public class Version2ContentAssignmentService {

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final ContentAssignmentService contentAssignmentService;
    private final ContentAssignmentStageResultCodec resultCodec;
    private final Version2ExecutionState executionState;

    public Version2ContentAssignmentService(ScanRepository scanRepository, JobRepository jobRepository,
            ContentAssignmentService contentAssignmentService,
            ContentAssignmentStageResultCodec resultCodec,
            Version2ExecutionState executionState) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.contentAssignmentService = contentAssignmentService;
        this.resultCodec = resultCodec;
        this.executionState = executionState;
    }

    public ContentAssignmentStageResult execute(long scanRunId) {
        throw new UnsupportedOperationException(
                "Historical v2 SCAN work cannot write the V6 catalog");
    }

    public ContentAssignmentStageResult executeVersion3(long scanRunId) {
        return execute(scanRunId, ScanExecutionDefinition.VERSION_3);
    }

    private ContentAssignmentStageResult execute(long scanRunId, long executionVersion) {
        AssignmentPlan plan = preflight(scanRunId, executionVersion);
        executionState.startStage(plan.job(), plan.stage(), System.currentTimeMillis());

        try {
            ContentAssignmentResult assignment = contentAssignmentService.assignSources(scanRunId, plan.sources());
            IndexingInterruptedException.check();
            ContentAssignmentStageResult result = ContentAssignmentStageResult.from(assignment);
            long processedCount = result.assignedCount() + result.skippedCount();
            executionState.completeAssignment(
                    plan.job(), plan.stage(), resultCodec.write(result), processedCount, System.currentTimeMillis());
            return result;
        } catch (RuntimeException exception) {
            IndexingInterruptedException.propagateIfInterrupted(exception);
            String errorMessage = "Content assignment failed";
            executionState.failCurrentStage(
                    scanRunId, plan.job(), plan.stage(), System.currentTimeMillis(), errorMessage);
            throw new Version2ExecutionFailedException(errorMessage, exception);
        }
    }

    private AssignmentPlan preflight(long scanRunId, long executionVersion) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        Job job = jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, executionVersion)
                .orElseThrow(() -> new NoSuchElementException(
                        "Version-2 execution for ScanRun " + scanRunId + " does not exist"));
        JobStage reconciliation = requireStage(job, ScanExecutionDefinition.RECONCILIATION);
        JobStage assignment = requireStage(job, ScanExecutionDefinition.CONTENT_ASSIGNMENT);

        if (!"RUNNING".equals(scanRun.status())
                || !"RUNNING".equals(job.status())
                || !ScanExecutionDefinition.CONTENT_ASSIGNMENT.equals(job.currentStageType())
                || !"COMPLETED".equals(reconciliation.status())
                || !"PENDING".equals(assignment.status())) {
            throw new Version2ExecutionConflictException(
                    "Execution is not eligible to start CONTENT_ASSIGNMENT");
        }

        List<ScanRunSource> sources = contentAssignmentService.requireCompletedSources(scanRunId);
        return new AssignmentPlan(job, assignment, sources);
    }

    private JobStage requireStage(Job job, String stageType) {
        return jobRepository.findJobStageByJobIdAndType(job.id(), stageType)
                .orElseThrow(() -> new Version2ExecutionConflictException(stageType + " stage does not exist"));
    }

    private record AssignmentPlan(Job job, JobStage stage, List<ScanRunSource> sources) {
    }
}
