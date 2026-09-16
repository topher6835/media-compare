package io.github.topher6835.mediacompare.analysis;

import java.util.List;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ScanExecutionDefinition;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunSource;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import io.github.topher6835.mediacompare.scan.Version2ExecutionFailedException;
import io.github.topher6835.mediacompare.scan.Version2ExecutionState;

import org.springframework.stereotype.Service;

@Service
public class Version2ContentHashingService {

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final ContentHashingService contentHashingService;
    private final ContentHashingStageResultCodec resultCodec;
    private final Version2ExecutionState executionState;

    public Version2ContentHashingService(ScanRepository scanRepository, JobRepository jobRepository,
            ContentHashingService contentHashingService,
            ContentHashingStageResultCodec resultCodec,
            Version2ExecutionState executionState) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.contentHashingService = contentHashingService;
        this.resultCodec = resultCodec;
        this.executionState = executionState;
    }

    public ContentHashingStageResult execute(long scanRunId) {
        HashingPlan plan = preflight(scanRunId);
        executionState.startStage(plan.job(), plan.stage(), System.currentTimeMillis());

        try {
            ContentHashingResult hashing = contentHashingService.hashSources(scanRunId, plan.sources());
            ContentHashingStageResult result = ContentHashingStageResult.from(hashing);
            long processedCount = result.hashedCount() + result.cachedCount()
                    + result.skippedCount() + result.failedCount();
            executionState.completeHashing(scanRunId, plan.job(), plan.stage(),
                    resultCodec.write(result), processedCount, System.currentTimeMillis());
            return result;
        } catch (RuntimeException exception) {
            String errorMessage = "Content hashing failed";
            executionState.failCurrentStage(
                    scanRunId, plan.job(), plan.stage(), System.currentTimeMillis(), errorMessage);
            throw new Version2ExecutionFailedException(errorMessage, exception);
        }
    }

    private HashingPlan preflight(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        Job job = jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, ScanExecutionDefinition.VERSION_2)
                .orElseThrow(() -> new NoSuchElementException(
                        "Version-2 execution for ScanRun " + scanRunId + " does not exist"));
        JobStage assignment = requireStage(job, ScanExecutionDefinition.CONTENT_ASSIGNMENT);
        JobStage hashing = requireStage(job, ScanExecutionDefinition.CONTENT_HASHING);

        if (!"RUNNING".equals(scanRun.status())
                || !"RUNNING".equals(job.status())
                || !ScanExecutionDefinition.CONTENT_HASHING.equals(job.currentStageType())
                || !"COMPLETED".equals(assignment.status())
                || !"PENDING".equals(hashing.status())) {
            throw new Version2ExecutionConflictException(
                    "Execution is not eligible to start CONTENT_HASHING");
        }

        List<ScanRunSource> sources = contentHashingService.requireCompletedSources(scanRunId);
        return new HashingPlan(job, hashing, sources);
    }

    private JobStage requireStage(Job job, String stageType) {
        return jobRepository.findJobStageByJobIdAndType(job.id(), stageType)
                .orElseThrow(() -> new Version2ExecutionConflictException(stageType + " stage does not exist"));
    }

    private record HashingPlan(Job job, JobStage stage, List<ScanRunSource> sources) {
    }
}
