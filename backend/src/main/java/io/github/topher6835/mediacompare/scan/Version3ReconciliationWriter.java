package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Version3ReconciliationWriter {
    private final SourceMembershipPublicationService memberships;
    private final JobRepository jobs;

    public Version3ReconciliationWriter(SourceMembershipPublicationService memberships,
            JobRepository jobs) {
        this.memberships = memberships;
        this.jobs = jobs;
    }

    @Transactional
    public void reconcile(long jobId, long stageId, ScanRunSource source,
            MissingClaimAuthority claim, long progress, long completedAtMs) {
        memberships.reconcile(claim, source, completedAtMs);
        if (jobs.updateReconciliationProgress(jobId, stageId,
                ScanExecutionDefinition.VERSION_3, progress) != 2) {
            throw new IllegalStateException("Could not update v3 reconciliation progress");
        }
    }
}
