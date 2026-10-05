package io.github.topher6835.mediacompare.contentread;

import io.github.topher6835.mediacompare.catalog.CurrentLocationAuthority;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.scan.ExfatScanBundles;
import io.github.topher6835.mediacompare.web.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Admission is gate → writer → commit. Execution receives immutable exact owners only. */
@Component
public class ExfatContentReadBundles {
    private record Prepared(ExfatAuthorityScope scope, ExfatAuthorityWindowRegistry.WindowId window) { }
    public record MetadataAdmission(MediaMetadataExecutionDetails details, boolean submit) { }
    private final ExfatAuthorityWindowRegistry registry;
    private final ExfatContentReadCatalog catalog;
    private final ExfatScanBundles scans;
    private final JobRepository jobs;
    private final TransactionTemplate transactions;
    private final boolean testEnabled;
    private final Map<Long, List<ExfatContentReadAuthority>> metadata = new ConcurrentHashMap<>();
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();

    @Autowired
    public ExfatContentReadBundles(ExfatAuthorityWindowRegistry registry, ExfatContentReadCatalog catalog,
            ExfatScanBundles scans, JobRepository jobs, PlatformTransactionManager manager) {
        this(registry, catalog, scans, jobs, manager, false);
    }
    ExfatContentReadBundles(ExfatAuthorityWindowRegistry registry, ExfatContentReadCatalog catalog,
            ExfatScanBundles scans, JobRepository jobs, PlatformTransactionManager manager, boolean testEnabled) {
        this.registry = registry; this.catalog = catalog; this.scans = scans; this.jobs = jobs;
        this.transactions = new TransactionTemplate(manager); this.testEnabled = testEnabled;
    }

    private SessionSourceBoundary boundary;
    @Autowired
    void sessionBoundary(SessionSourceBoundary boundary) { this.boundary = boundary; }

    private void freshSeparation(List<Prepared> captures) {
        if (boundary == null) return;
        for (var p : captures) {
            var root = CurrentLocationAuthority
                    .requirePersisted(p.scope().source(), p.scope().context()).root();
            try { boundary.requireFreshSeparate(new WindowsNtfsHostFileSystem().pathText(root)); }
            catch (java.io.IOException failure) { throw new IllegalStateException("Fresh Session/Source separation unavailable", failure); }
        }
    }

    public MetadataAdmission admitMetadata(CreateMediaMetadataRunRequest request,
            Supplier<MediaMetadataExecutionDetails> create) {
        requireEnabled();
        var captured = request.indexingScanRunId() == null ? prepared(request.authorityWindows())
                : scans.requireHandoff(request.indexingScanRunId()).authorities().stream()
                    .map(a -> new Prepared(a.scope(), a.window())).toList();
        freshSeparation(captured);
        return registry.transition(() -> {
            final long[] accepted = {0};
            try {
                return transactions.execute(status -> {
                    jobs.reserveAdmissionWrite();
                    ExfatScanBundles.Handoff handoff = request.indexingScanRunId() == null ? null
                            : scans.requireHandoff(request.indexingScanRunId());
                    if (handoff != null && handoff.metadataJobId() != null) {
                        var owners = metadata.get(handoff.metadataJobId());
                        if (owners == null) throw ExfatContentReadCatalog.unavailable();
                        checkOwners(owners);
                        var job = jobs.findJobById(handoff.metadataJobId()).orElseThrow();
                        return new MetadataAdmission(new MediaMetadataExecutionDetails(job,
                                jobs.findJobStagesByJobId(job.id())), false);
                    }
                    for (var p : captured) if (!catalog.scope(p.window().sourceId()).equals(p.scope())) throw ExfatContentReadCatalog.unavailable();
                    var prepared = captured;
                    var details = create.get();
                    accepted[0] = details.job().id();
                    String bundle = handoff == null ? UUID.randomUUID().toString() : handoff.bundleId();
                    var owners = prepared.stream().map(p -> new ExfatContentReadAuthority(p.scope(), p.window(),
                            bundle, details.job().id(), null)).toList();
                    registry.associateContent(owners, handoff == null ? null : handoff.bundleId());
                    metadata.put(details.job().id(), List.copyOf(owners));
                    if (handoff != null) scans.bindHandoff(handoff, details.job().id());
                    checkOwners(owners);
                    return new MetadataAdmission(details, true);
                });
            } catch (RuntimeException failure) {
                if (accepted[0] != 0) finishMetadata(accepted[0]);
                throw failure;
            }
        });
    }

    private List<Prepared> prepared(List<SourceAuthorityWindowRequest> requested) {
        return requested.stream().map(r -> {
            var scope = catalog.scope(r.sourceId());
            var id = new ExfatAuthorityWindowRegistry.WindowId(registry.runtimeId(), r.sourceId(), r.windowId());
            // This is an exact supplied UUID, never capturePrepared/current-window lookup.
            try (var ignored = registry.requireExact(scope, id)) { }
            return new Prepared(scope, id);
        }).toList();
    }

    public List<ExfatContentReadAuthority> metadataOwners(long jobId) {
        return metadata.getOrDefault(jobId, List.of());
    }

    public void publishing(List<ExfatContentReadAuthority> owners, Runnable transaction) {
        var leases = new ArrayList<ExfatAuthorityWindowRegistry.Operation>();
        try {
            for (var owner : owners) leases.add(registry.requireContent(owner));
            transaction.run();
            for (var lease : leases) lease.progress(); // Actual catalog progress, including cache-only pages.
        } finally { for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close(); }
    }

    private void checkOwners(List<ExfatContentReadAuthority> owners) {
        for (var owner : owners) {
            if (!catalog.scope(owner.window().sourceId()).equals(owner.scope())) throw ExfatContentReadCatalog.unavailable();
            try (var ignored = registry.requireContent(owner)) { }
        }
    }

    /** Called after actual worker exit, or for a task that was never started. Idempotent. */
    public void finishMetadata(long jobId) {
        var owners = metadata.remove(jobId);
        if (owners != null) registry.revokeContent(owners.getFirst().bundleId());
        scans.forgetHandoff(jobId);
    }

    public Batch admitBatch(List<SourceAuthorityWindowRequest> windows) {
        requireEnabled();
        var captured = prepared(windows);
        freshSeparation(captured);
        return registry.transition(() -> transactions.execute(status -> {
            jobs.reserveAdmissionWrite();
            if (batches.size() >= 64) throw ExfatContentReadCatalog.unavailable();
            String id = UUID.randomUUID().toString();
            for (var p : captured) if (!catalog.scope(p.window().sourceId()).equals(p.scope())) throw ExfatContentReadCatalog.unavailable();
            var owners = captured.stream().map(p -> new ExfatContentReadAuthority(
                    p.scope(), p.window(), id, null, id)).toList();
            registry.associateContent(owners, null);
            Batch batch = new Batch(id, owners);
            batches.put(id, batch);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) batch.abortAdmission();
                        }
                    });
            return batch;
        }));
    }

    /** Submission owns a reference until seal; fast workers cannot prematurely release the set. */
    public final class Batch {
        private final String id;
        private final List<ExfatContentReadAuthority> owners;
        private int remaining, admittedCount;
        private boolean sealed, drained;
        private Batch(String id, List<ExfatContentReadAuthority> owners) { this.id = id; this.owners = List.copyOf(owners); }
        public List<ExfatContentReadAuthority> owners() { return owners; }
        public synchronized Item admitItem() {
            if (sealed || drained || admittedCount == 100) throw new IllegalStateException("Batch is sealed or full");
            admittedCount++; remaining++; return new Item(this);
        }
        public synchronized void seal() { sealed = true; drainIfTerminal(); }
        private synchronized void exited() { remaining--; drainIfTerminal(); }
        private synchronized void abortAdmission() { sealed = true; drainIfTerminal(); }
        private void drainIfTerminal() {
            if (sealed && remaining == 0 && !drained) {
                drained = true; batches.remove(id, this); registry.revokeContent(id);
            }
        }
        public synchronized boolean drained() { return drained; }
    }
    public static final class Item implements AutoCloseable {
        private final Batch batch;
        private boolean exited;
        private Item(Batch batch) { this.batch = batch; }
        @Override public synchronized void close() { if (!exited) { exited = true; batch.exited(); } }
    }

    private void requireEnabled() {
        if (!testEnabled && !WindowsExfatSupport.PRODUCTION.available()) {
            throw new MediaMetadataJobConflictException("Production exFAT content reads are disabled");
        }
    }
}
