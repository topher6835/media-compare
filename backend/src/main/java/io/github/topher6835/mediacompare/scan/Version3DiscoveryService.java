package io.github.topher6835.mediacompare.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityOutcome;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;
import io.github.topher6835.mediacompare.scan.authority.TraversalCompletion;

import org.springframework.stereotype.Service;

/** v3 discovery: fresh authority, mount-aware traversal, and trusted completion. */
@Service
public class Version3DiscoveryService {
    private final ScanRepository scans;
    private final JobRepository jobs;
    private final CatalogRepository catalog;
    private final Version3AuthorityCapture capture;
    private final Version3DiscoveryWalker walker;
    private final DiscoveryBatchWriter batches;
    private final DiscoveryExecutionState state;
    private final Version3DiscoveryCompletionWriter completion;

    public Version3DiscoveryService(ScanRepository scans, JobRepository jobs, CatalogRepository catalog,
            Version3AuthorityCapture capture, Version3DiscoveryWalker walker,
            DiscoveryBatchWriter batches, DiscoveryExecutionState state,
            Version3DiscoveryCompletionWriter completion) {
        this.scans = scans;
        this.jobs = jobs;
        this.catalog = catalog;
        this.capture = capture;
        this.walker = walker;
        this.batches = batches;
        this.state = state;
        this.completion = completion;
    }

    public Map<Long, MissingClaimAuthority> execute(long scanRunId) {
        Plan plan = preflight(scanRunId);
        state.start(scanRunId, plan.job(), plan.stage(), plan.sources(), System.currentTimeMillis());
        Map<Long, MissingClaimAuthority> claims = new HashMap<>();
        long discovered = 0;
        DiscoverySource active = plan.sources().getFirst();
        try {
            for (DiscoverySource source : plan.sources()) {
                IndexingInterruptedException.check();
                active = source;
                var start = capture.capture(source.source().id());
                if (start.outcome() != ScanAuthorityOutcome.TRUSTED) {
                    throw new IllegalStateException("Current discovery authority is unavailable for Source "
                            + source.source().id() + ": " + start.reason());
                }
                var snapshot = start.value().orElseThrow();
                Path root = Path.of(source.source().rootPath());
                List<ResolvedFileCandidate> batch = new ArrayList<>(DiscoveryBatchWriter.MAX_BATCH_SIZE);
                long[] count = { discovered };
                TraversalCompletion.Issue issue = walker.walk(root, snapshot, candidate -> {
                    batch.add(candidate);
                    if (batch.size() == DiscoveryBatchWriter.MAX_BATCH_SIZE) {
                        count[0] = flush(plan, source, batch, count[0]);
                    }
                });
                if (!batch.isEmpty()) {
                    count[0] = flush(plan, source, batch, count[0]);
                }
                discovered = count[0];
                var end = capture.capture(source.source().id());
                var result = ScanObservationAuthority.assessTraversal(start, end,
                        new TraversalCompletion(snapshot.sourceRoot(), snapshot.sourceRoot(), issue));
                if (result.outcome() != ScanAuthorityOutcome.TRUSTED) {
                    throw new IllegalStateException("Traversal cannot authorize reconciliation for Source "
                            + source.source().id() + ": " + result.reason());
                }
                claims.put(source.source().id(), result.value().orElseThrow());
            }
            completion.complete(plan.job(), plan.stage(), plan.sources(), claims,
                    discovered, System.currentTimeMillis());
            return Map.copyOf(claims);
        } catch (IOException | RuntimeException exception) {
            IndexingInterruptedException.propagateIfInterrupted(exception);
            String message = "Version-3 discovery failed for Source " + active.source().id();
            state.fail(scanRunId, plan.job(), plan.stage(), plan.sources(), active,
                    System.currentTimeMillis(), message);
            throw new IllegalStateException(message, exception);
        }
    }

    private long flush(Plan plan, DiscoverySource source, List<ResolvedFileCandidate> batch,
            long previous) {
        IndexingInterruptedException.check();
        long current = previous + batch.size();
        batches.writeResolved(plan.job().id(), plan.stage().id(), source.scanRunSource().id(),
                source.traversalGeneration(), List.copyOf(batch), current);
        batch.clear();
        return current;
    }

    private Plan preflight(long scanRunId) {
        ScanRun run = scans.findScanRunById(scanRunId).orElseThrow();
        Job job = jobs.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, "SCAN", ScanExecutionDefinition.VERSION_3).orElseThrow();
        JobStage stage = jobs.findJobStageByJobIdAndType(job.id(), ScanExecutionDefinition.DISCOVERY)
                .orElseThrow();
        if (!"PENDING".equals(run.status()) || !"PENDING".equals(job.status())
                || !"PENDING".equals(stage.status())
                || !ScanExecutionDefinition.DISCOVERY.equals(job.currentStageType())) {
            throw new IllegalStateException("Version-3 discovery is not pending");
        }
        List<DiscoverySource> sources = new ArrayList<>();
        for (ScanRunSource row : scans.findScanRunSourcesByScanRunId(scanRunId)) {
            var source = catalog.findSourceById(row.sourceId()).orElseThrow();
            if (!"PENDING".equals(row.status()) || row.traversalGeneration() != 0
                    || source.locationRevision() != row.sourceLocationRevision()) {
                throw new IllegalStateException("Version-3 Source discovery snapshot changed");
            }
            sources.add(new DiscoverySource(source, row, 1));
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("Version-3 scan has no Sources");
        }
        return new Plan(job, stage, List.copyOf(sources));
    }

    private record Plan(Job job, JobStage stage, List<DiscoverySource> sources) {
    }
}
