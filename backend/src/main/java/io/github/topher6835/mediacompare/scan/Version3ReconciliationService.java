package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;

import org.springframework.stereotype.Service;

/** Reconciles only the complete v3 traversals produced by the same execution. */
@Service
public class Version3ReconciliationService {
    private final ScanRepository scans;
    private final JobRepository jobs;
    private final ReconciliationExecutionState state;
    private final Version3ReconciliationWriter writer;
    private final Version2ExecutionState failureState;

    public Version3ReconciliationService(ScanRepository scans, JobRepository jobs,
            ReconciliationExecutionState state, Version3ReconciliationWriter writer,
            Version2ExecutionState failureState) {
        this.scans = scans;
        this.jobs = jobs;
        this.state = state;
        this.writer = writer;
        this.failureState = failureState;
    }

    public void execute(long scanRunId, Map<Long, MissingClaimAuthority> claims) {
        Job job = jobs.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, "SCAN", ScanExecutionDefinition.VERSION_3).orElseThrow();
        JobStage discovery = jobs.findJobStageByJobIdAndType(job.id(),
                ScanExecutionDefinition.DISCOVERY).orElseThrow();
        JobStage stage = jobs.findJobStageByJobIdAndType(job.id(),
                ScanExecutionDefinition.RECONCILIATION).orElseThrow();
        ScanRun run = scans.findScanRunById(scanRunId).orElseThrow();
        List<ScanRunSource> sources = scans.findScanRunSourcesByScanRunId(scanRunId);
        if (!"RUNNING".equals(run.status()) || !"RUNNING".equals(job.status())
                || !ScanExecutionDefinition.RECONCILIATION.equals(job.currentStageType())
                || !"COMPLETED".equals(discovery.status()) || !"PENDING".equals(stage.status())
                || claims.size() != sources.size()
                || sources.stream().anyMatch(source -> !"DISCOVERED".equals(source.status()))) {
            throw new IllegalStateException("Version-3 reconciliation is not eligible");
        }
        state.start(job, stage, sources.size(), System.currentTimeMillis());
        try {
            long completed = 0;
            for (ScanRunSource source : sources) {
                IndexingInterruptedException.check();
                MissingClaimAuthority claim = claims.get(source.sourceId());
                if (claim == null) {
                    throw new IllegalStateException("Source has no trusted missing-claim authority");
                }
                writer.reconcile(job.id(), stage.id(), source, claim,
                        ++completed, System.currentTimeMillis());
            }
            state.complete(scanRunId, job, stage, sources.size(), System.currentTimeMillis());
        } catch (RuntimeException exception) {
            IndexingInterruptedException.propagateIfInterrupted(exception);
            failureState.failCurrentStage(scanRunId, job, stage, System.currentTimeMillis(),
                    "Version-3 reconciliation failed");
            throw exception;
        }
    }
}
