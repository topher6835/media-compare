package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {

    private static final String SCAN_JOB_TYPE = "SCAN";
    private static final String RUNNING_STATUS = "RUNNING";
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String PENDING_STATUS = "PENDING";
    private static final String DISCOVERED_STATUS = "DISCOVERED";
    private static final String DISCOVERY_STAGE_TYPE = "DISCOVERY";
    private static final String RECONCILIATION_STAGE_TYPE = "RECONCILIATION";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final ReconciliationWriter reconciliationWriter;
    private final ReconciliationExecutionState executionState;

    public ReconciliationService(ScanRepository scanRepository, JobRepository jobRepository,
            ReconciliationWriter reconciliationWriter, ReconciliationExecutionState executionState) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.reconciliationWriter = reconciliationWriter;
        this.executionState = executionState;
    }

    public ScanExecutionDetails execute(long scanRunId) {
        ReconciliationPlan plan = preflight(scanRunId);
        long sourceCount = plan.sources().size();
        executionState.start(plan.job(), plan.reconciliationStage(), sourceCount,
                System.currentTimeMillis());

        long progressCompleted = 0;
        for (ScanRunSource source : plan.sources()) {
            progressCompleted++;
            reconciliationWriter.reconcile(
                    plan.job().id(), plan.reconciliationStage().id(), source,
                    progressCompleted, System.currentTimeMillis());
        }

        executionState.complete(scanRunId, plan.job(), plan.reconciliationStage(), sourceCount,
                System.currentTimeMillis());
        Job completedJob = jobRepository.findJobById(plan.job().id()).orElseThrow();
        return new ScanExecutionDetails(completedJob,
                jobRepository.findJobStagesByJobId(completedJob.id()));
    }

    private ReconciliationPlan preflight(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> sources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);
        Job job = jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(job.id(), DISCOVERY_STAGE_TYPE)
                .orElseThrow(() -> new ReconciliationConflictException("DISCOVERY stage does not exist"));
        JobStage reconciliationStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), RECONCILIATION_STAGE_TYPE)
                .orElseThrow(() -> new ReconciliationConflictException("RECONCILIATION stage does not exist"));

        if (!RUNNING_STATUS.equals(scanRun.status())
                || !SCAN_JOB_TYPE.equals(job.jobType())
                || !RUNNING_STATUS.equals(job.status())
                || !RECONCILIATION_STAGE_TYPE.equals(job.currentStageType())
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
