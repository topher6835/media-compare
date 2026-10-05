package io.github.topher6835.mediacompare.filesystem;

import io.github.topher6835.mediacompare.location.LocationPathCodec;

import java.io.IOException;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Process-local authority. The publication gate is always acquired before a catalog writer. */
public final class ExfatAuthorityWindowRegistry implements DisposableBean {
    public enum Phase { PREPARED, IN_BUNDLE, HANDOFF, REVOKED, CLOSED }
    public enum ReleaseState { RELEASED, DRAINING }
    public record WindowId(String runtimeId, long sourceId, String windowId) {
        public WindowId { ExfatReceiptValues.uuid(runtimeId); ExfatReceiptValues.positive(sourceId);
            ExfatReceiptValues.uuid(windowId); }
    }
    public record LiveAuthority(boolean liveAuthorityAvailable, String liveAuthorityWindowId) { }
    public record ReleaseResult(ReleaseState releaseState, boolean otherWindowsOnVolumeRemain) { }
    public static final Duration PREPARE_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration HANDOFF_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration NO_PROGRESS_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DRAIN_BUDGET = Duration.ofSeconds(30);
    private static final int MAX_WINDOWS = 64, MAX_DIRECTORIES = 4096;

    private final Object monitor = new Object();
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final String runtimeId = UUID.randomUUID().toString();
    private final Map<Long, Window> current = new HashMap<>();
    private final Map<WindowId, Window> closing = new HashMap<>();
    private final Map<Long, Attempt> attempts = new HashMap<>();
    private final Map<Long, Long> generations = new HashMap<>();
    private final LongSupplier ticks;
    private final Duration drainBudget;
    private final Duration noProgressTimeout;
    private final CatalogOwnership ownership;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-compare-exfat-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private int directories;
    private boolean shutdown;

    public ExfatAuthorityWindowRegistry(CatalogOwnership ownership) {
        this(ownership, System::nanoTime, DRAIN_BUDGET);
    }

    // Internal clock/budget injection; no system property or production activation seam.
    ExfatAuthorityWindowRegistry(CatalogOwnership ownership, LongSupplier ticks, Duration drainBudget) {
        this(ownership, ticks, drainBudget, NO_PROGRESS_TIMEOUT);
    }

    ExfatAuthorityWindowRegistry(CatalogOwnership ownership, LongSupplier ticks, Duration drainBudget, Duration noProgressTimeout) {
        if (noProgressTimeout.isZero() || noProgressTimeout.isNegative()) throw new IllegalArgumentException("Invalid no-progress deadline");
        this.ownership = ownership;
        this.ticks = ticks;
        this.drainBudget = drainBudget;
        this.noProgressTimeout = noProgressTimeout;
        maintenance.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    public String runtimeId() { return runtimeId; }

    /** An uncertain rejected-acquisition close must retain catalog ownership and deny further acquisition. */
    public void cleanupFailed() { shutdown(); ownership.retainUntilProcessExit(); }

    public boolean ownsSource(long sourceId) {
        synchronized (monitor) { return current.containsKey(sourceId) || attempts.containsKey(sourceId)
                || closing.keySet().stream().anyMatch(id -> id.sourceId() == sourceId); }
    }

    public boolean ownsContext(String contextId) {
        synchronized (monitor) { return current.values().stream().anyMatch(w -> w.scope.context().id().equals(contextId))
                || closing.values().stream().anyMatch(w -> w.scope.context().id().equals(contextId)); }
    }

    /** Reserve limits and an exact generation before native IO. One Prepare attempt per Source. */
    public Attempt begin(long sourceId, int directoryCount) {
        return begin(sourceId, directoryCount, null);
    }

    public Attempt begin(long sourceId, int directoryCount, String contextId) {
        ExfatReceiptValues.positive(sourceId, directoryCount);
        if (contextId != null) ExfatReceiptValues.uuid(contextId);
        if (directoryCount > 257) throw invalid();
        synchronized (monitor) {
            long scopes = current.size() + attempts.keySet().stream().filter(id -> !current.containsKey(id)).count();
            if (shutdown || attempts.containsKey(sourceId) || !current.containsKey(sourceId) && scopes >= MAX_WINDOWS
                    || directories + directoryCount > MAX_DIRECTORIES) throw invalid();
            Attempt attempt = new Attempt(sourceId, generations.getOrDefault(sourceId, 0L), directoryCount, contextId);
            attempts.put(sourceId, attempt);
            directories += directoryCount;
            return attempt;
        }
    }

    public final class Attempt implements AutoCloseable {
        private final ExfatAuthorityWindowRegistry owner = ExfatAuthorityWindowRegistry.this;
        private final String id = UUID.randomUUID().toString();
        private final long sourceId, generation, started = ticks.getAsLong();
        private final int count;
        private final String contextId;
        private boolean cancelled, finished;
        private Attempt(long sourceId, long generation, int count, String contextId) {
            this.sourceId = sourceId; this.generation = generation; this.count = count; this.contextId = contextId;
        }
        public String id() { return id; }
        public void checkpoint() {
            synchronized (monitor) {
                if (shutdown || cancelled || finished || attempts.get(sourceId) != this
                        || generation != generations.getOrDefault(sourceId, 0L)
                        || ticks.getAsLong() - started >= PREPARE_TIMEOUT.toNanos()) throw invalid();
            }
        }
        @Override public void close() {
            synchronized (monitor) {
                if (!finished) {
                    finished = true;
                    cancelled = true;
                    attempts.remove(sourceId, this);
                    directories -= count;
                    monitor.notifyAll();
                }
            }
        }
    }

    /** Caller retains this outer gate through TransactionTemplate/proxy commit, then installation. */
    public <T> T installing(Attempt attempt, Supplier<T> committedAction) {
        requireAttemptOwner(attempt);
        requireOutsideTransaction();
        gate.writeLock().lock();
        try { attempt.checkpoint(); return committedAction.get(); }
        finally { gate.writeLock().unlock(); }
    }

    /** Takes ownership only on success; callers close rejected acquisitions in finally. */
    public WindowId install(Attempt attempt, ExfatAuthorityScope scope, ExfatRetainedRoot root) {
        requireAttemptOwner(attempt);
        requireOutsideTransaction();
        if (!gate.isWriteLockedByCurrentThread()) throw new IllegalStateException("Installation requires outer gate");
        var evidence = new WindowsExfatEvidenceCodec().decodeSource(scope.source().bindingEvidenceJson());
        var path = new LocationPathCodec()
                .decode(evidence.resolvedRootLocationPath());
        if (!root.available() || root.directoryCount() != attempt.count || !root.resolvedRoot().equals(path)
                || !root.volumeEvidence().equals(evidence.volume()) || scope.source().id() != attempt.sourceId
                || attempt.contextId != null && !attempt.contextId.equals(scope.context().id())) throw invalid();
        Window old;
        Window window = new Window(scope, root);
        root.claimOwnership(this);
        synchronized (monitor) {
            attempt.checkpoint();
            old = current.put(attempt.sourceId, window);
            if (old != null) revokeLocked(old);
            attempts.remove(attempt.sourceId, attempt);
            attempt.finished = true; // Its reserved directory count transfers to the installed window.
        }
        if (old != null) drainAsync(old);
        return window.id;
    }

    /** Finish fresh retained revalidation without renewing a deadline or changing UUID. */
    public WindowId retain(Attempt attempt, ExfatAuthorityScope scope, WindowId id) {
        requireAttemptOwner(attempt);
        requireOutsideTransaction();
        if (!gate.isWriteLockedByCurrentThread()) throw new IllegalStateException("Revalidation requires outer gate");
        if (scope.source().id() != attempt.sourceId
                || attempt.contextId != null && !attempt.contextId.equals(scope.context().id())) throw invalid();
        synchronized (monitor) {
            attempt.checkpoint();
            requireLocked(scope, id);
            attempt.close();
            return id;
        }
    }

    /** Catalog/registry projection only. No native probe or acquisition occurs here. */
    public LiveAuthority project(ExfatAuthorityScope scope) {
        synchronized (monitor) {
            Window window = current.get(scope.source().id());
            if (window == null || !usable(window) || !window.scope.equals(scope)) return new LiveAuthority(false, null);
            return new LiveAuthority(true, window.id.windowId());
        }
    }

    public WindowId capturePrepared(ExfatAuthorityScope scope) {
        synchronized (monitor) {
            Window window = current.get(scope.source().id());
            if (window == null || !window.scope.equals(scope) || !usable(window)) throw invalid();
            return window.id;
        }
    }

    /** Exact UUID, runtime and durable snapshots; holding this lease also holds the publication read gate. */
    public Operation requireExact(ExfatAuthorityScope scope, WindowId id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && gate.getReadHoldCount() == 0 && !gate.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Acquire publication gate before entering a catalog transaction");
        }
        gate.readLock().lock();
        try {
            synchronized (monitor) {
                Window window = requireLocked(scope, id);
                window.users++;
                return new Operation(window);
            }
        } catch (RuntimeException failure) { gate.readLock().unlock(); throw failure; }
    }

    public final class Operation implements AutoCloseable {
        private final Window window;
        private final boolean holdsPublicationGate;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Operation(Window window) { this(window, true); }
        private Operation(Window window, boolean holdsPublicationGate) {
            this.window = window; this.holdsPublicationGate = holdsPublicationGate;
            if (window.contentOwner != null) window.workers.merge(owner, 1, Integer::sum);
        }
        public void revalidate() throws IOException {
            requireOwner();
            if (closed) throw invalid();
            try { window.root.revalidate(); }
            catch (IOException | RuntimeException failure) {
                invalidateVolume(window.scope.context().id());
                throw failure;
            }
            synchronized (monitor) { requireLocked(window.scope, window.id); }
        }
        public boolean cancelled() { synchronized (monitor) { return !usable(window); } }
        /** Pure exact-reference check, including bundle ownership; no native IO. */
        public void checkpoint(String bundleId, long scanRunId, long jobId) {
            requireOwner();
            synchronized (monitor) {
                if (closed || requireLocked(window.scope, window.id) != window
                        || window.phase != Phase.IN_BUNDLE || !java.util.Objects.equals(window.bundleId, bundleId)
                        || window.scanRunId != scanRunId || window.jobId != jobId) throw invalid();
            }
        }
        public void checkpointContent(ExfatContentReadAuthority authority) {
            requireOwner();
            synchronized (monitor) {
                if (closed || requireLocked(authority.scope(), authority.window()) != window
                        || window.phase != Phase.IN_BUNDLE || !authority.equals(window.contentOwner)) throw invalid();
            }
            if (Thread.currentThread().isInterrupted()) throw invalid();
        }
        public DirectoryReservation reserveDirectories(int count) {
            requireOwner();
            synchronized (monitor) {
                if (closed || !usable(window) || count < 1 || directories + count > MAX_DIRECTORIES) throw invalid();
                directories += count;
                return new DirectoryReservation(count);
            }
        }
        /** Actual IO/catalog progress renews inactivity only, never the authority UUID. */
        public void progress() {
            requireOwner();
            synchronized (monitor) {
                if (closed || requireLocked(window.scope, window.id) != window || window.phase != Phase.IN_BUNDLE) throw invalid();
                window.lastProgress = ticks.getAsLong();
            }
        }
        @Override public void close() {
            requireOwner();
            if (closed) return;
            closed = true;
            synchronized (monitor) {
                window.users--;
                window.workers.computeIfPresent(owner, (thread, count) -> count == 1 ? null : count - 1);
                monitor.notifyAll();
            }
            if (holdsPublicationGate) gate.readLock().unlock();
        }
        private void requireOwner() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Operation leases are thread-confined");
        }
    }

    /** Retain resources for acquisition/hash IO without holding the short publication gate. */
    public Operation retainBundle(ExfatAuthorityScope scope, WindowId id, String bundleId, long scanRunId, long jobId) {
        requireOutsideTransaction();
        synchronized (monitor) {
            Window window = requireLocked(scope, id);
            if (window.phase != Phase.IN_BUNDLE || !java.util.Objects.equals(window.bundleId, bundleId)
                    || window.scanRunId != scanRunId || window.jobId != jobId) throw invalid();
            window.users++;
            return new Operation(window, false);
        }
    }

    /** Later reads have their own owner; no synthetic SCAN/generation identifiers. */
    public Operation retainContent(ExfatContentReadAuthority authority) {
        requireOutsideTransaction();
        synchronized (monitor) {
            Window window = requireLocked(authority.scope(), authority.window());
            if (!authority.equals(window.contentOwner) || window.phase != Phase.IN_BUNDLE) throw invalid();
            window.users++;
            return new Operation(window, false);
        }
    }

    public Operation requireContent(ExfatContentReadAuthority authority) {
        Operation operation = requireExact(authority.scope(), authority.window());
        try { operation.checkpointContent(authority); return operation; }
        catch (RuntimeException failure) { operation.close(); throw failure; }
    }

    public boolean liveHandoff(ExfatAuthorityScope scope, WindowId id, String bundle) {
        synchronized (monitor) {
            try {
                Window window = requireLocked(scope, id);
                return window.phase == Phase.HANDOFF && bundle.equals(window.bundleId);
            } catch (IllegalStateException unavailable) { return false; }
        }
    }

    /** Validate the entire exact set before transferring any member; caller holds gate through commit. */
    public void associateContent(java.util.List<ExfatContentReadAuthority> authorities, String handoffBundle) {
        if (!gate.isWriteLockedByCurrentThread() || authorities.isEmpty()) throw invalid();
        synchronized (monitor) {
            var seen = new java.util.HashSet<Long>();
            var first = authorities.getFirst();
            for (var a : authorities) {
                Window w = requireLocked(a.scope(), a.window());
                if (!seen.add(a.window().sourceId()) || !first.bundleId().equals(a.bundleId())
                        || !java.util.Objects.equals(first.metadataJobId(), a.metadataJobId())
                        || !java.util.Objects.equals(first.thumbnailBatchId(), a.thumbnailBatchId())
                        || (handoffBundle == null ? w.phase != Phase.PREPARED
                            : w.phase != Phase.HANDOFF || !handoffBundle.equals(w.bundleId))) throw invalid();
            }
            for (var a : authorities) {
                Window w = requireLocked(a.scope(), a.window());
                w.contentOwner = a; w.bundleId = a.bundleId();
                w.jobId = a.metadataJobId() == null ? 0 : a.metadataJobId();
                w.phase = Phase.IN_BUNDLE; w.lastProgress = ticks.getAsLong();
            }
        }
    }

    public void revokeContent(String bundle) {
        synchronized (monitor) {
            for (Window w : new ArrayList<>(current.values())) {
                if (w.contentOwner != null && bundle.equals(w.contentOwner.bundleId())) revokeBundleLocked(w);
            }
        }
    }

    public final class DirectoryReservation implements AutoCloseable {
        private final int count;
        private boolean closed;
        private DirectoryReservation(int count) { this.count = count; }
        @Override public void close() {
            synchronized (monitor) { if (!closed) { closed = true; directories -= count; } }
        }
    }

    private Window requireLocked(ExfatAuthorityScope scope, WindowId id) {
        Window window = current.get(scope.source().id());
        if (id == null || !runtimeId.equals(id.runtimeId()) || window == null || !window.id.equals(id)
                || !window.scope.equals(scope) || !usable(window)) throw invalid();
        return window;
    }

    private boolean usable(Window window) {
        return !shutdown && window.phase != Phase.REVOKED && window.phase != Phase.CLOSED
                && window.root.available() && (window.phase == Phase.IN_BUNDLE
                    ? ticks.getAsLong() - window.lastProgress < noProgressTimeout.toNanos()
                    : ticks.getAsLong() - window.phaseStarted < timeout(window.phase).toNanos());
    }

    private static Duration timeout(Phase phase) { return phase == Phase.HANDOFF ? HANDOFF_TIMEOUT : PREPARE_TIMEOUT; }

    /** All members are checked before any association changes; caller brackets the catalog commit. */
    public void associateAll(java.util.List<Association> associations) {
        if (!gate.isWriteLockedByCurrentThread()) throw new IllegalStateException("Admission requires outer gate");
        synchronized (monitor) {
            var seen = new java.util.HashSet<Long>();
            Association first = associations.getFirst();
            for (Association member : associations) {
                ExfatReceiptValues.uuid(member.bundleId());
                ExfatReceiptValues.positive(member.scanRunId(), member.jobId());
                Window window = requireLocked(member.scope(), member.id());
                if (!seen.add(member.id().sourceId()) || window.phase != Phase.PREPARED
                        || !first.bundleId().equals(member.bundleId()) || first.scanRunId() != member.scanRunId()
                        || first.jobId() != member.jobId()) throw invalid();
            }
            for (Association member : associations) {
                Window window = requireLocked(member.scope(), member.id());
                window.bundleId = member.bundleId(); window.scanRunId = member.scanRunId();
                window.jobId = member.jobId(); window.phase = Phase.IN_BUNDLE; window.lastProgress = ticks.getAsLong();
            }
        }
    }

    public record Association(ExfatAuthorityScope scope, WindowId id, String bundleId, long scanRunId, long jobId) { }

    public void revokeJob(long jobId) {
        synchronized (monitor) {
            for (Window window : new ArrayList<>(current.values())) {
                if (window.jobId == jobId) revokeBundleLocked(window);
            }
        }
    }

    /** Single-member lifecycle compatibility hook. */
    public void associate(ExfatAuthorityScope scope, WindowId id, String bundleId, long scanRunId, long jobId) {
        ExfatReceiptValues.uuid(bundleId); ExfatReceiptValues.positive(scanRunId, jobId);
        gate.writeLock().lock();
        try { synchronized (monitor) {
            Window window = requireLocked(scope, id);
            if (window.phase != Phase.PREPARED) throw invalid();
            window.bundleId = bundleId; window.scanRunId = scanRunId; window.jobId = jobId;
            window.phase = Phase.IN_BUNDLE;
            window.lastProgress = ticks.getAsLong();
        } } finally { gate.writeLock().unlock(); }
    }

    public Operation requireBundle(ExfatAuthorityScope scope, WindowId id, String bundleId, long jobId) {
        Operation operation = requireExact(scope, id);
        synchronized (monitor) {
            if (!java.util.Objects.equals(operation.window.bundleId, bundleId) || operation.window.jobId != jobId
                    || operation.window.phase != Phase.IN_BUNDLE) {
                operation.close(); throw invalid();
            }
        }
        return operation;
    }

    public void handoff(ExfatAuthorityScope scope, WindowId id, String bundleId) {
        gate.writeLock().lock();
        try { synchronized (monitor) {
            Window window = requireLocked(scope, id);
            if (window.phase != Phase.IN_BUNDLE || !java.util.Objects.equals(bundleId, window.bundleId)) throw invalid();
            window.phase = Phase.HANDOFF; window.phaseStarted = ticks.getAsLong();
        } } finally { gate.writeLock().unlock(); }
    }

    public ReleaseResult release(WindowId id) {
        return release(id, null);
    }

    /** Optional durable context hint is only for the other-window projection on an already closed UUID. */
    public ReleaseResult release(WindowId id, String contextId) {
        Window window;
        synchronized (monitor) {
            if (!runtimeId.equals(id.runtimeId())) throw invalid();
            window = current.get(id.sourceId());
            if (window != null && window.id.equals(id)) {
                // Stale releases never cancel a newer Prepare attempt.
                generations.merge(id.sourceId(), 1L, Math::addExact);
                Attempt pending = attempts.get(id.sourceId());
                if (pending != null) pending.cancelled = true;
                revokeBundleLocked(window);
            } else window = closing.get(id);
        }
        if (window == null) return new ReleaseResult(ReleaseState.RELEASED, otherOnVolume(contextId, id));
        drainAsync(window);
        long end = System.nanoTime() + drainBudget.toNanos();
        synchronized (monitor) {
            while (window.phase != Phase.CLOSED && window.closeFailure == null) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) break;
                try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); break; }
            }
            return new ReleaseResult(window.phase == Phase.CLOSED ? ReleaseState.RELEASED : ReleaseState.DRAINING,
                    otherOnVolume(window.scope.context().id(), id));
        }
    }

    private boolean otherOnVolume(String contextId, WindowId excluded) {
        synchronized (monitor) {
            if (contextId == null) return false;
            return current.values().stream().anyMatch(w -> !w.id.equals(excluded)
                    && contextId.equals(w.scope.context().id()))
                    || closing.values().stream().anyMatch(w -> !w.id.equals(excluded)
                        && contextId.equals(w.scope.context().id()));
        }
    }

    private void revokeBundleLocked(Window window) {
        for (Window member : new ArrayList<>(current.values())) {
            if (member == window || window.bundleId != null && window.bundleId.equals(member.bundleId)) {
                revokeLocked(member); drainAsync(member);
            }
        }
    }

    private void revokeLocked(Window window) {
        window.phase = Phase.REVOKED;
        // Checkpoints stop bounded reads; interruption also reaches cooperative decoders.
        for (Thread worker : window.workers.keySet()) {
            if (worker != Thread.currentThread()) worker.interrupt();
        }
        current.remove(window.id.sourceId(), window);
        closing.put(window.id, window);
    }

    public void invalidateSource(long sourceId) {
        synchronized (monitor) {
            generations.merge(sourceId, 1L, Math::addExact);
            Attempt attempt = attempts.get(sourceId);
            if (attempt != null) attempt.cancelled = true;
            Window window = current.get(sourceId);
            if (window != null) revokeBundleLocked(window);
        }
    }

    public void invalidateVolume(String contextId) {
        synchronized (monitor) {
            for (Attempt attempt : attempts.values()) {
                if (contextId.equals(attempt.contextId)) attempt.cancelled = true;
            }
            for (Window window : new ArrayList<>(current.values())) {
                if (window.scope.context().id().equals(contextId)) invalidateSource(window.id.sourceId());
            }
        }
    }

    /** Durable lifecycle coordinators hold this gate across writer reservation and commit. */
    public <T> T transition(Supplier<T> transaction) {
        requireOutsideTransaction();
        gate.writeLock().lock();
        try { return transaction.get(); }
        finally { gate.writeLock().unlock(); }
    }

    private void drainAsync(Window window) {
        synchronized (monitor) {
            if (window.drainStarted) return;
            window.drainStarted = true;
        }
        Thread drain = new Thread(() -> {
            // Wait without the gate: an IO user must be able to leave or attempt (and reject) publication.
            synchronized (monitor) {
                while (window.users != 0) {
                    try { monitor.wait(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); return; }
                }
            }
            gate.writeLock().lock();
            try {
                try {
                    window.root.close();
                    synchronized (monitor) {
                        window.phase = Phase.CLOSED;
                        directories -= window.root.directoryCount();
                        closing.remove(window.id);
                    }
                } catch (IOException | RuntimeException failure) {
                    synchronized (monitor) { window.closeFailure = failure; }
                    cleanupFailed();
                }
            } finally {
                gate.writeLock().unlock();
                synchronized (monitor) { monitor.notifyAll(); }
            }
        }, "media-compare-exfat-drain");
        drain.setDaemon(true);
        drain.start();
    }

    void expire() {
        synchronized (monitor) {
            for (Attempt attempt : attempts.values()) {
                if (ticks.getAsLong() - attempt.started >= PREPARE_TIMEOUT.toNanos()) attempt.cancelled = true;
            }
            for (Window window : new ArrayList<>(current.values())) {
                if (!usable(window)) invalidateSource(window.id.sourceId());
            }
        }
    }

    /** Called before executor draining; new admissions and pending installations immediately fail. */
    public void shutdown() {
        synchronized (monitor) {
            shutdown = true;
            for (Attempt attempt : attempts.values()) attempt.cancelled = true;
            for (Window window : new ArrayList<>(current.values())) revokeBundleLocked(window);
        }
    }

    @Override public void destroy() {
        shutdown();
        maintenance.shutdownNow();
        long end = System.nanoTime() + drainBudget.toNanos();
        synchronized (monitor) {
            while (!closing.isEmpty() || !attempts.isEmpty()) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0 || closing.values().stream().anyMatch(w -> w.closeFailure != null)) break;
                try { TimeUnit.NANOSECONDS.timedWait(monitor, remaining); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); break; }
            }
            if (!closing.isEmpty() || !attempts.isEmpty()) ownership.retainUntilProcessExit();
        }
    }

    private final class Window {
        final WindowId id;
        final ExfatAuthorityScope scope;
        final ExfatRetainedRoot root;
        final Instant acquiredAt = Instant.now();
        Phase phase = Phase.PREPARED;
        long phaseStarted = ticks.getAsLong();
        long lastProgress = phaseStarted;
        String bundleId;
        ExfatContentReadAuthority contentOwner;
        final Map<Thread, Integer> workers = new HashMap<>();
        long scanRunId, jobId;
        int users;
        boolean drainStarted;
        Exception closeFailure;
        Window(ExfatAuthorityScope scope, ExfatRetainedRoot root) {
            this.scope = scope; this.root = root;
            id = new WindowId(runtimeId, scope.source().id(), UUID.randomUUID().toString());
        }
    }

    private void requireAttemptOwner(Attempt attempt) {
        if (attempt == null || attempt.owner != this) throw invalid();
    }

    private static void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Runtime installation/lifecycle must bracket the catalog transaction through commit");
        }
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Exact exFAT authority unavailable"); }
}
