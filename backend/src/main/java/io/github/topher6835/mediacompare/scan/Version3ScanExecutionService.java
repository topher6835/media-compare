package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.topher6835.mediacompare.analysis.Version2ContentHashingService;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityOutcome;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityReason;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** New SCAN admission and four-stage execution under membership authority. */
@Service
public class Version3ScanExecutionService {
    private final ScanRepository scans;
    private final JobRepository jobs;
    private final CatalogRepository catalog;
    private final LocationContextRepository contexts;
    private final Version3DiscoveryService discovery;
    private final Version3ReconciliationService reconciliation;
    private final Version2ContentAssignmentService assignment;
    private final Version2ContentHashingService hashing;

    public Version3ScanExecutionService(ScanRepository scans, JobRepository jobs,
            CatalogRepository catalog, LocationContextRepository contexts,
            Version3DiscoveryService discovery, Version3ReconciliationService reconciliation,
            Version2ContentAssignmentService assignment, Version2ContentHashingService hashing) {
        this.scans = scans;
        this.jobs = jobs;
        this.catalog = catalog;
        this.contexts = contexts;
        this.discovery = discovery;
        this.reconciliation = reconciliation;
        this.assignment = assignment;
        this.hashing = hashing;
    }

    @Transactional
    public ScanExecutionDetails create(long scanRunId) {
        scans.reserveExecutionWrite();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            throw new Version2ExecutionConflictException(
                    "Version-3 SCAN requires a local macOS/APFS host");
        }
        ScanRun scan = scans.findScanRunById(scanRunId).orElseThrow();
        if (!"INDEX".equals(scan.requestType()) || !"PENDING".equals(scan.status())
                || jobs.findJobByScanRunIdAndType(scanRunId, "SCAN").isPresent()
                || jobs.hasActiveScanJob()) {
            throw new Version2ExecutionConflictException("ScanRun is not eligible for a v3 execution");
        }
        List<ScanRunSource> sources = scans.findScanRunSourcesByScanRunId(scanRunId);
        if (sources.isEmpty()) {
            throw new Version2ExecutionConflictException("SCAN requires at least one Source");
        }
        for (ScanRunSource row : sources) {
            var source = catalog.findSourceById(row.sourceId()).orElseThrow();
            if (source.locationRevision() != row.sourceLocationRevision()
                    || source.boundLocationContextId() == null) {
                throw new Version2ExecutionConflictException("Source is unbound or stale for v3 admission");
            }
            var context = contexts.findById(source.boundLocationContextId()).orElse(null);
            if (context == null) {
                throw new Version2ExecutionConflictException("Source context is unavailable for v3 admission");
            }
            var authority = ScanObservationAuthority.capture(source, context, null, null);
            if (authority.outcome() != ScanAuthorityOutcome.UNAVAILABLE
                    || authority.reason() != ScanAuthorityReason.AUTHORITY_UNAVAILABLE) {
                throw new Version2ExecutionConflictException(
                        "Source lacks supported current binding authority for v3 admission: "
                                + authority.reason());
            }
        }
        long now = System.currentTimeMillis();
        Job job = jobs.insert(new Job(null, scanRunId, "SCAN", ScanExecutionDefinition.VERSION_3,
                "PENDING", ScanExecutionDefinition.DISCOVERY, 0, null, 0,
                now, null, null, null));
        JobStage stage = jobs.insert(new JobStage(null, job.id(),
                ScanExecutionDefinition.DISCOVERY, null, "PENDING", 0, null, 0,
                now, null, null, null));
        return new ScanExecutionDetails(job, List.of(stage));
    }

    public Optional<ScanExecutionDetails> findByScanRunId(long scanRunId) {
        return jobs.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, "SCAN", ScanExecutionDefinition.VERSION_3)
                .map(job -> new ScanExecutionDetails(job, jobs.findJobStagesByJobId(job.id())));
    }

    public ScanExecutionDetails run(long scanRunId) {
        var claims = discovery.execute(scanRunId);
        reconciliation.execute(scanRunId, claims);
        assignment.executeVersion3(scanRunId);
        hashing.executeVersion3(scanRunId);
        return findByScanRunId(scanRunId).orElseThrow();
    }
}
