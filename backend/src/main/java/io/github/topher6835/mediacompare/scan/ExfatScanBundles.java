package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest;
import io.github.topher6835.mediacompare.web.CreateMediaMetadataRunRequest;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityUnavailableException;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadAuthority;
import io.github.topher6835.mediacompare.filesystem.WindowsDurableEvidenceFormat;
import io.github.topher6835.mediacompare.job.Job;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriodRepository;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityScope;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry.WindowId;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatSupport;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.scan.authority.WindowsExfatScanAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runtime-only admission and ownership. Nothing here is reconstructed from a receipt. */
@Component
public class ExfatScanBundles {
    private final CatalogRepository catalog;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final ScanRepository scans;
    private final JobRepository jobs;
    private final ExfatAuthorityWindowRegistry registry;
    private final TransactionTemplate transactions;
    private final boolean testEnabled;
    private final Map<Long, List<WindowsExfatScanAuthority>> admitted = new ConcurrentHashMap<>();

    @Autowired
    public ExfatScanBundles(CatalogRepository catalog, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, ScanRepository scans, JobRepository jobs,
            ExfatAuthorityWindowRegistry registry, PlatformTransactionManager manager) {
        this(catalog, contexts, periods, scans, jobs, registry, manager, false);
    }

    // Explicit package/test construction only; never selected by environment, properties or HTTP.
    ExfatScanBundles(CatalogRepository catalog, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, ScanRepository scans, JobRepository jobs,
            ExfatAuthorityWindowRegistry registry, PlatformTransactionManager manager, boolean testEnabled) {
        this.catalog = catalog; this.contexts = contexts; this.periods = periods;
        this.scans = scans; this.jobs = jobs; this.registry = registry;
        transactions = new TransactionTemplate(manager); this.testEnabled = testEnabled;
        handoffExpiry.scheduleWithFixedDelay(() -> registry.transition(() -> { pruneHandoffs(); return null; }), 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    public record Handoff(long scanRunId, long scanJobId, String runtimeId, String bundleId,
            List<WindowsExfatScanAuthority> authorities, Long metadataJobId) {
        public Handoff { authorities = List.copyOf(authorities); }
    }
    private final Map<Long, Handoff> handoffs = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService handoffExpiry =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "media-compare-exfat-handoffs");
                thread.setDaemon(true); return thread;
            });

    public Handoff requireHandoff(long scanRunId) {
        Handoff descriptor = handoffs.get(scanRunId);
        if (descriptor == null) throw stale();
        for (var a : descriptor.authorities()) {
            if (descriptor.metadataJobId() == null) {
                if (!registry.liveHandoff(a.scope(), a.window(), descriptor.bundleId())) throw stale();
            } else {
                var owner = new ExfatContentReadAuthority(a.scope(), a.window(), descriptor.bundleId(), descriptor.metadataJobId(), null);
                try (var ignored = registry.requireContent(owner)) { }
            }
        }
        var scan = scans.findScanRunById(scanRunId).orElseThrow();
        var job = jobs.findJobById(descriptor.scanJobId()).orElseThrow();
        if (!"COMPLETED".equals(scan.status()) || !"COMPLETED".equals(job.status())
                || !"SCAN".equals(job.jobType()) || job.executionVersion() != 3
                || !Long.valueOf(scanRunId).equals(job.scanRunId())) throw stale();
        for (var a : descriptor.authorities()) {
            var row = scans.findScanRunSourceById(a.scanRunSourceId()).orElseThrow();
            if (!scope(a.scope().source().id()).equals(a.scope()) || !"COMPLETED".equals(row.status())
                    || row.scanRunId() != scanRunId || row.sourceId() != a.scope().source().id()
                    || row.sourceLocationRevision() != a.scope().source().locationRevision()
                    || row.traversalGeneration() != a.generation()
                    || !Long.valueOf(a.generation()).equals(row.completedGeneration())) throw stale();
        }
        return descriptor;
    }

    public void bindHandoff(Handoff handoff, long metadataJobId) {
        if (handoff.metadataJobId() != null || !handoffs.replace(handoff.scanRunId(), handoff,
                new Handoff(handoff.scanRunId(), handoff.scanJobId(), handoff.runtimeId(), handoff.bundleId(),
                        handoff.authorities(), metadataJobId))) throw stale();
    }

    public void forgetHandoff(long metadataJobId) {
        handoffs.entrySet().removeIf(entry -> Long.valueOf(metadataJobId).equals(entry.getValue().metadataJobId()));
    }

    private void pruneHandoffs() {
        handoffs.entrySet().removeIf(entry -> {
            Handoff h = entry.getValue();
            if (h.metadataJobId() == null) return h.authorities().stream().anyMatch(a ->
                    !registry.liveHandoff(a.scope(), a.window(), a.bundleUuid()));
            for (var a : h.authorities()) {
                var content = new ExfatContentReadAuthority(
                        a.scope(), a.window(), h.bundleId(), h.metadataJobId(), null);
                try (var ignored = registry.retainContent(content)) { }
                catch (IllegalStateException unavailable) { return true; }
            }
            return false;
        });
    }

    @jakarta.annotation.PreDestroy
    void closeHandoffMaintenance() { handoffExpiry.shutdownNow(); handoffs.clear(); }

    public record Prepared(ExfatAuthorityScope scope, WindowId window) { }

    public ScanExecutionDetails admit(List<Long> sourceIds,
            Function<List<Prepared>, ScanExecutionDetails> create) {
        return admit(sourceIds, List.of(), create);
    }

    public ScanExecutionDetails admit(List<Long> sourceIds,
            List<SourceAuthorityWindowRequest> requested,
            Function<List<Prepared>, ScanExecutionDetails> create) {
        outsideTransaction();
        ScanRunService.validateSourceIds(sourceIds);
        var windows = requested == null || requested.isEmpty() ? List.<SourceAuthorityWindowRequest>of()
                : CreateMediaMetadataRunRequest.checkedWindows(requested);
        var bySource = new java.util.HashMap<Long, String>();
        for (var w : windows) bySource.put(w.sourceId(), w.windowId());
        var captured = new ArrayList<Prepared>();
        for (long sourceId : sourceIds) {
            var source = catalog.findSourceById(sourceId).orElseThrow();
            if ("win-drive".equals(source.rootPathDialect()) && WindowsDurableEvidenceFormat
                    .identify(source.bindingEvidenceJson()) == WindowsDurableEvidenceFormat.EXFAT_SOURCE) {
                if (!testEnabled && !WindowsExfatSupport.PRODUCTION.available())
                    throw new Version2ExecutionConflictException("Production exFAT indexing is disabled");
                String uuid = bySource.remove(sourceId);
                if (uuid == null) throw new Version2ExecutionConflictException("exFAT requires an explicit authority window");
                try {
                    var scope = scope(sourceId);
                    var window = new WindowId(registry.runtimeId(), sourceId, uuid);
                    try (var ignored = registry.requireExact(scope, window)) { }
                    captured.add(new Prepared(scope, window));
                } catch (IllegalStateException unavailable) {
                    throw new Version2ExecutionConflictException("Exact exFAT authority unavailable");
                }
            }
        }
        if (!bySource.isEmpty()) throw new IllegalArgumentException("Extra authority Source");
        if (captured.isEmpty()) return transactions.execute(status -> create.apply(List.of()));
        return admitCaptured(List.copyOf(captured), create);
    }

    ScanExecutionDetails admitCaptured(List<Prepared> captured,
            Function<List<Prepared>, ScanExecutionDetails> create) {
        outsideTransaction();
        if (!testEnabled && !WindowsExfatSupport.PRODUCTION.available()) throw new Version2ExecutionConflictException("Production exFAT indexing is disabled");
        final long[] jobId = {0};
        return registry.transition(() -> {
            try {
                return transactions.execute(status -> {
                    for (Prepared p : captured) {
                        if (!scope(p.scope().source().id()).equals(p.scope())) throw stale();
                        try (var lease = registry.requireExact(p.scope(), p.window())) { /* Exact capture only. */ }
                    }
                    ScanExecutionDetails execution = create.apply(captured);
                    jobId[0] = execution.job().id();
                    String bundle = UUID.randomUUID().toString();
                    var authorities = captured.stream().map(p -> {
                        var row = scans.findScanRunSourcesByScanRunId(execution.job().scanRunId()).stream()
                                .filter(r -> r.sourceId() == p.scope().source().id()).findFirst().orElseThrow();
                        if (!"PENDING".equals(row.status()) || row.traversalGeneration() != 0
                                || row.sourceLocationRevision() != p.scope().source().locationRevision()) throw stale();
                        return new WindowsExfatScanAuthority(p.scope(), p.window(), bundle,
                                row.scanRunId(), execution.job().id(), row.id(), 1);
                    }).toList();
                    registry.associateAll(authorities.stream().map(a -> new ExfatAuthorityWindowRegistry.Association(
                            a.scope(), a.window(), bundle, a.scanRunId(), a.jobId())).toList());
                    if (admitted.putIfAbsent(execution.job().scanRunId(), authorities) != null) throw stale();
                    return execution;
                });
            } catch (RuntimeException failure) {
                if (jobId[0] != 0) revokeJob(jobId[0]);
                throw failure;
            }
        });
    }

    public List<WindowsExfatScanAuthority> authorities(long scanRunId) {
        return admitted.getOrDefault(scanRunId, List.of());
    }

    public List<WindowsExfatScanAuthority> checkedAuthorities(long scanRunId) {
        for (var row : scans.findScanRunSourcesByScanRunId(scanRunId)) {
            var source = catalog.findSourceById(row.sourceId()).orElseThrow();
            if ("win-drive".equals(source.rootPathDialect()) && WindowsDurableEvidenceFormat
                    .identify(source.bindingEvidenceJson()) == WindowsDurableEvidenceFormat.EXFAT_SOURCE) {
                require(scanRunId, source.id());
            }
        }
        return authorities(scanRunId);
    }

    public WindowsExfatScanAuthority require(long scanRunId, long sourceId) {
        return authorities(scanRunId).stream().filter(a -> a.scope().source().id() == sourceId)
                .findFirst().orElseThrow(ExfatScanBundles::stale);
    }

    public Lease lease(List<WindowsExfatScanAuthority> authorities) {
        return lease(authorities, true);
    }

    public Lease retain(List<WindowsExfatScanAuthority> authorities) {
        return lease(authorities, false);
    }

    private Lease lease(List<WindowsExfatScanAuthority> authorities, boolean publication) {
        outsideTransaction();
        var leases = new ArrayList<ExfatAuthorityWindowRegistry.Operation>();
        try {
            for (var a : authorities) {
                if (!require(a.scanRunId(), a.scope().source().id()).equals(a)) throw stale();
                var op = publication ? registry.requireBundle(a.scope(), a.window(), a.bundleUuid(), a.jobId())
                        : registry.retainBundle(a.scope(), a.window(), a.bundleUuid(), a.scanRunId(), a.jobId());
                leases.add(op);
                op.checkpoint(a.bundleUuid(), a.scanRunId(), a.jobId());
            }
            return new Lease(List.copyOf(authorities), leases);
        } catch (RuntimeException failure) {
            for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close();
            throw failure;
        }
    }

    public final class Lease implements AutoCloseable {
        private final List<WindowsExfatScanAuthority> authorities;
        private final List<ExfatAuthorityWindowRegistry.Operation> operations;
        private Lease(List<WindowsExfatScanAuthority> authorities, List<ExfatAuthorityWindowRegistry.Operation> operations) {
            this.authorities = authorities; this.operations = operations;
        }
        public void checkpoint() {
            IndexingInterruptedException.check();
            for (int i = 0; i < authorities.size(); i++) {
                var a = authorities.get(i);
                operations.get(i).checkpoint(a.bundleUuid(), a.scanRunId(), a.jobId());
            }
        }
        public void revalidate() throws IOException {
            outsideTransaction();
            for (var operation : operations) operation.revalidate();
            checkpoint();
        }
        public void progress() {
            checkpoint();
            for (var operation : operations) operation.progress();
        }
        public ExfatAuthorityWindowRegistry.DirectoryReservation reserveDirectories(int count) {
            checkpoint(); return operations.getFirst().reserveDirectories(count);
        }
        @Override public void close() {
            for (int i = operations.size() - 1; i >= 0; i--) operations.get(i).close();
        }
    }

    /** Catalog checks only, called after taking a lease and reserving the writer. */
    public void requireCatalog(WindowsExfatScanAuthority a, String stage, String sourceStatus) {
        // Reentrant only under the outer lease. Registry rejects writer-before-gate callers.
        try (var held = registry.requireBundle(a.scope(), a.window(), a.bundleUuid(), a.jobId())) {
            held.checkpoint(a.bundleUuid(), a.scanRunId(), a.jobId());
        }
        if (!require(a.scanRunId(), a.scope().source().id()).equals(a) || !scope(a.scope().source().id()).equals(a.scope())) throw stale();
        var job = jobs.findJobById(a.jobId()).orElseThrow();
        var row = scans.findScanRunSourceById(a.scanRunSourceId()).orElseThrow();
        if (!"SCAN".equals(job.jobType()) || job.executionVersion() != 3 || !"RUNNING".equals(job.status())
                || !Long.valueOf(a.scanRunId()).equals(job.scanRunId()) || !stage.equals(job.currentStageType())
                || !"RUNNING".equals(scans.findScanRunById(a.scanRunId()).orElseThrow().status())
                || row.scanRunId() != a.scanRunId() || row.sourceId() != a.scope().source().id()
                || row.sourceLocationRevision() != a.scope().source().locationRevision()
                || row.traversalGeneration() != a.generation() || !sourceStatus.equals(row.status())
                || ("COMPLETED".equals(sourceStatus) && !Long.valueOf(a.generation()).equals(row.completedGeneration()))) throw stale();
        var jobStage = jobs.findJobStageByJobIdAndType(a.jobId(), stage).orElseThrow();
        if (!"RUNNING".equals(jobStage.status())) throw stale();
    }

    public void revokeJob(long jobId) {
        registry.revokeJob(jobId);
        admitted.entrySet().removeIf(entry -> entry.getValue().stream().anyMatch(a -> a.jobId() == jobId));
    }

    public void requireStageCompletion(Job job) {
        for (var a : checkedAuthorities(job.scanRunId())) requireCatalog(a, job.currentStageType(), "COMPLETED");
    }

    public void publishing(long scanRunId, Runnable transaction) {
        try (var lease = lease(checkedAuthorities(scanRunId))) {
            lease.checkpoint(); transaction.run(); lease.progress();
        }
    }

    public void progress(long scanRunId) {
        try (var lease = retain(authorities(scanRunId))) { lease.progress(); }
    }

    public void handoff(long scanRunId) {
        if (authorities(scanRunId).isEmpty()) return;
        registry.transition(() -> {
            var captured = List.copyOf(authorities(scanRunId));
            pruneHandoffs();
            if (handoffs.size() >= 64) throw stale();
            for (var a : captured) registry.handoff(a.scope(), a.window(), a.bundleUuid());
            var first = captured.getFirst();
            handoffs.put(scanRunId, new Handoff(scanRunId, first.jobId(), registry.runtimeId(),
                    first.bundleUuid(), captured, null));
            admitted.remove(scanRunId);
            return null;
        });
    }

    private ExfatAuthorityScope scope(long sourceId) {
        var source = catalog.findSourceById(sourceId).orElseThrow();
        return new ExfatAuthorityScope(source, contexts.findById(source.boundLocationContextId()).orElseThrow(),
                periods.findOpenBySourceId(sourceId).orElseThrow());
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Take exFAT gate before catalog transaction");
    }
    private static IllegalStateException stale() { return new ExfatAuthorityUnavailableException("Exact exFAT scan authority changed or unavailable"); }
}
