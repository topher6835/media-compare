package io.github.topher6835.mediacompare.scan;

import java.util.Optional;

import io.github.topher6835.mediacompare.job.JobRepository;

import org.springframework.stereotype.Service;

/** Historical v1/v2 execution reads; V6 admits and executes new SCAN work as v3. */
@Service
public class ScanExecutionService {
    private final ScanRepository scans;
    private final JobRepository jobs;

    public ScanExecutionService(ScanRepository scans, JobRepository jobs) {
        this.scans = scans;
        this.jobs = jobs;
    }

    public Optional<ScanExecutionDetails> findByScanRunId(long scanRunId) {
        return findByScanRunIdAndVersion(scanRunId, ScanExecutionDefinition.VERSION_1);
    }

    public Optional<ScanExecutionDetails> findByScanRunIdAndVersion(long scanRunId, long version) {
        if (scans.findScanRunById(scanRunId).isEmpty()) {
            return Optional.empty();
        }
        return jobs.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, version)
                .map(job -> new ScanExecutionDetails(job, jobs.findJobStagesByJobId(job.id())));
    }

    public ScanExecutionDetails create(long scanRunId) {
        throw new ScanExecutionAlreadyExistsException(scanRunId);
    }

    public ScanExecutionDetails executeDiscovery(long scanRunId) {
        throw new DiscoveryConflictException("Historical discovery cannot write the V6 catalog");
    }

    public ScanExecutionDetails executeVersion2Discovery(long scanRunId) {
        throw new DiscoveryConflictException("Historical discovery cannot write the V6 catalog");
    }
}
