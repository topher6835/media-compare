package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {

    private static final String RUNNING_STATUS = "RUNNING";
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String PENDING_STATUS = "PENDING";
    private static final String DISCOVERED_STATUS = "DISCOVERED";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final ReconciliationWriter reconciliationWriter;
    private final ReconciliationExecutionState executionState;
    private final Version2ExecutionState version2ExecutionState;

    public ReconciliationService(ScanRepository scanRepository, JobRepository jobRepository,
            ReconciliationWriter reconciliationWriter, ReconciliationExecutionState executionState,
            Version2ExecutionState version2ExecutionState) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.reconciliationWriter = reconciliationWriter;
        this.executionState = executionState;
        this.version2ExecutionState = version2ExecutionState;
    }

    public ScanExecutionDetails execute(long scanRunId) {
        return execute(scanRunId, ScanExecutionDefinition.VERSION_1);
    }

    public ScanExecutionDetails executeVersion2(long scanRunId) {
        return execute(scanRunId, ScanExecutionDefinition.VERSION_2);
    }

    private ScanExecutionDetails execute(long scanRunId, long executionVersion) {
        ReconciliationPlan plan = preflight(scanRunId, executionVersion);
        long sourceCount = plan.sources().size();
        executionState.start(plan.job(), plan.reconciliationStage(), sourceCount,
                System.currentTimeMillis());

        try {
            long progressCompleted = 0;
            for (ScanRunSource source : plan.sources()) {
                IndexingInterruptedException.check();
                progressCompleted++;
                reconciliationWriter.reconcile(
                        plan.job().id(), plan.reconciliationStage().id(), executionVersion,
                        source, progressCompleted, System.currentTimeMillis());
            }

            executionState.complete(scanRunId, plan.job(), plan.reconciliationStage(), sourceCount,
                    System.currentTimeMillis());
        } catch (RuntimeException exception) {
            IndexingInterruptedException.propagateIfInterrupted(exception);
            if (executionVersion != ScanExecutionDefinition.VERSION_2) {
                throw exception;
            }
            String errorMessage = "Reconciliation failed";
            version2ExecutionState.failCurrentStage(
                    scanRunId, plan.job(), plan.reconciliationStage(), System.currentTimeMillis(), errorMessage);
            throw new Version2ExecutionFailedException(errorMessage, exception);
        }
        Job completedJob = jobRepository.findJobById(plan.job().id()).orElseThrow();
        return new ScanExecutionDetails(completedJob,
                jobRepository.findJobStagesByJobId(completedJob.id()));
    }

    private ReconciliationPlan preflight(long scanRunId, long executionVersion) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> sources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);
        Job job = jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, executionVersion)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.DISCOVERY)
                .orElseThrow(() -> new ReconciliationConflictException("DISCOVERY stage does not exist"));
        JobStage reconciliationStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.RECONCILIATION)
                .orElseThrow(() -> new ReconciliationConflictException("RECONCILIATION stage does not exist"));

        if (!RUNNING_STATUS.equals(scanRun.status())
                || !ScanExecutionDefinition.JOB_TYPE.equals(job.jobType())
                || job.executionVersion() != executionVersion
                || !RUNNING_STATUS.equals(job.status())
                || !ScanExecutionDefinition.RECONCILIATION.equals(job.currentStageType())
                || !COMPLETED_STATUS.equals(discoveryStage.status())
                || !PENDING_STATUS.equals(reconciliationStage.status())) {
            throw new ReconciliationConflictException("Execution is not eligible to start RECONCILIATION");
        }

        for (ScanRunSource source : sources) {
            if (!DISCOVERED_STATUS.equals(source.status())
                    || source.traversalGeneration() <= 0
                    || source.completedGeneration() != null
                    || source.completedAtMs() != null) {
                throw new ReconciliationConflictException(
                        "Source " + source.sourceId() + " is not eligible for RECONCILIATION");
            }
        }

        return new ReconciliationPlan(job, reconciliationStage, List.copyOf(sources));
    }

    private record ReconciliationPlan(Job job, JobStage reconciliationStage,
            List<ScanRunSource> sources) {
    }
}
