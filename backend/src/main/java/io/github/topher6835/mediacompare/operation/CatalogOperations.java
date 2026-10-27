package io.github.topher6835.mediacompare.operation;

import java.time.Clock;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small process-local lane for three interactive operations, using the existing worker shutdown pattern. */
@Component
public final class CatalogOperations implements DisposableBean {
    public enum State { RESERVED, QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
    public record Status(String operationId, CatalogOperationRequest.Kind kind, State status,
            long createdAtMs, Long startedAtMs, Long completedAtMs, JsonNode result,
            Integer errorStatus, String errorCode) { }
    private static final Logger log = LoggerFactory.getLogger(CatalogOperations.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RECORDS = 64;
    private static final int MAX_RESULT_BYTES = 64 * 1024;
    private final String runtimeId = UUID.randomUUID().toString();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final CatalogOwnership ownership;
    private final Function<CatalogOperationRequest, Object> actions;
    private final LongSupplier ticks;
    private final Clock clock;
    private final Duration retention;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "media-compare-catalog-operation");
                thread.setDaemon(false);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-compare-operation-expiry");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closing;

    @Autowired
    public CatalogOperations(CatalogOwnership ownership, CatalogOperationActions actions) {
        this(ownership, actions::execute, System::nanoTime, Clock.systemUTC(), Duration.ofMinutes(10));
    }

    // Controlled operations, clock and shutdown budgets keep tests fast and deterministic.
    CatalogOperations(CatalogOwnership ownership, Function<CatalogOperationRequest, Object> actions,
            LongSupplier ticks, Clock clock, Duration retention) {
        this.ownership = ownership;
        this.actions = actions;
        this.ticks = ticks;
        this.clock = clock;
        this.retention = retention;
        expiry.scheduleWithFixedDelay(this::prune, 1, 1, TimeUnit.MINUTES);
    }

    public synchronized Status reserve() {
        requireOpen();
        prune();
        if (entries.size() >= MAX_RECORDS) {
            // Evict only terminal observations. Unknown handles can never allocate replacement work.
            String removable = entries.values().stream().filter(entry -> entry.completedAtMs != null)
                    .map(entry -> entry.id).findFirst().orElse(null);
            if (removable != null) entries.remove(removable);
        }
        if (entries.size() >= MAX_RECORDS) throw new CatalogOperationFailure(429, "OPERATION_STORE_FULL");
        Entry entry = new Entry(runtimeId + "." + UUID.randomUUID());
        entries.put(entry.id, entry);
        return snapshot(entry);
    }

    public synchronized Status submit(String id, CatalogOperationRequest request) {
        requireOpen();
        Entry entry = requireEntry(id);
        if (entry.request != null) {
            if (!entry.request.equals(request)) throw new CatalogOperationFailure(409, "OPERATION_INPUT_CHANGED");
            return snapshot(entry); // Replays, including terminal ones, never execute twice.
        }
        if (entry.state != State.RESERVED) throw new CatalogOperationFailure(409, "OPERATION_CANCELLED");
        entry.request = request;
        entry.state = State.QUEUED;
        entry.task = () -> run(entry);
        try { executor.execute(entry.task); }
        catch (RejectedExecutionException busy) {
            entry.request = null;
            entry.state = State.RESERVED;
            entry.task = null;
            throw new CatalogOperationFailure(429, "OPERATION_BUSY");
        }
        return snapshot(entry);
    }

    public synchronized Status status(String id) { return snapshot(requireEntry(id)); }

    public String runtimeId() { return runtimeId; }

    public synchronized Status cancel(String id) {
        requireOpen();
        Entry entry = requireEntry(id);
        if (entry.request != null && entry.request.kind() != CatalogOperationRequest.Kind.CLEANUP_PREFLIGHT
                && (entry.state == State.RUNNING || entry.state == State.CANCELLING)) {
            throw new CatalogOperationFailure(409, "SIDE_EFFECT_IN_PROGRESS");
        }
        requestCancel(entry);
        return snapshot(entry);
    }

    private void requestCancel(Entry entry) {
        if (entry.state == State.FAILED || entry.state == State.CANCELLED) return;
        if (entry.state == State.SUCCEEDED && entry.request.kind() != CatalogOperationRequest.Kind.CLEANUP_PREFLIGHT) return;
        if (entry.state == State.RESERVED || entry.state == State.SUCCEEDED
                || entry.state == State.QUEUED && executor.remove(entry.task)) {
            finish(entry, State.CANCELLED, null, null, null);
        } else {
            entry.state = State.CANCELLING;
            if (entry.runner != null) entry.runner.interrupt();
        }
    }

    private void run(Entry entry) {
        synchronized (this) {
            if (closing || entry.state == State.CANCELLING || entry.state == State.CANCELLED) {
                finish(entry, State.CANCELLED, null, null, null);
                return;
            }
            entry.state = State.RUNNING;
            entry.startedAtMs = clock.millis();
            entry.runner = Thread.currentThread();
        }
        JsonNode result = null;
        Integer errorStatus = null;
        String errorCode = null;
        try {
            Object value = actions.apply(entry.request);
            var output = new LimitedResult();
            try { JSON.writeValue(output, value); }
            catch (RuntimeException failure) {
                if (output.exceeded) throw new CatalogOperationFailure(500, "RESULT_TOO_LARGE");
                throw failure;
            }
            result = JSON.readTree(output.toByteArray());
        } catch (CatalogOperationFailure failure) {
            errorStatus = failure.status();
            errorCode = failure.code();
        } catch (Throwable failure) {
            errorStatus = 500;
            errorCode = "OPERATION_FAILED";
            log.error("Catalog operation failed: {}", entry.id, failure);
            if (failure instanceof Error error) throw error;
        } finally {
            synchronized (this) {
                if (closing || entry.state == State.CANCELLING) finish(entry, State.CANCELLED, null, null, null);
                else finish(entry, errorStatus == null ? State.SUCCEEDED : State.FAILED, result, errorStatus, errorCode);
                entry.runner = null;
            }
            // Do not leak a cancellation interrupt into the next operation on the same worker.
            Thread.interrupted();
        }
    }

    private void finish(Entry entry, State state, JsonNode result, Integer errorStatus, String errorCode) {
        entry.state = state;
        entry.result = result;
        entry.errorStatus = errorStatus;
        entry.errorCode = errorCode;
        entry.completedAtMs = clock.millis();
        entry.finishedTick = ticks.getAsLong();
    }

    private Entry requireEntry(String id) {
        if (!id.startsWith(runtimeId + ".")) throw new CatalogOperationFailure(409, "CATALOG_CHANGED");
        prune();
        Entry entry = entries.get(id);
        // Unknown handles never allocate work, including after expiry and catalog restart.
        if (entry == null) throw new CatalogOperationFailure(410, "OPERATION_EXPIRED");
        return entry;
    }

    private synchronized void prune() {
        long now = ticks.getAsLong();
        entries.values().removeIf(entry -> (entry.state == State.RESERVED || entry.completedAtMs != null)
                && now - (entry.completedAtMs == null ? entry.createdTick : entry.finishedTick) >= retention.toNanos());
    }

    private void requireOpen() {
        if (closing) throw new CatalogOperationFailure(503, "CATALOG_STOPPING");
    }

    private Status snapshot(Entry entry) {
        return new Status(entry.id, entry.request == null ? null : entry.request.kind(), entry.state,
                entry.createdAtMs, entry.startedAtMs, entry.completedAtMs, entry.result, entry.errorStatus, entry.errorCode);
    }

    @Override public void destroy() { shutdown(Duration.ofSeconds(30)); }

    void shutdown(Duration budget) {
        synchronized (this) {
            closing = true;
            entries.values().forEach(this::requestCancel);
        }
        expiry.shutdownNow();
        executor.shutdownNow();
        try {
            if (executor.awaitTermination(budget.toMillis(), TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        ownership.retainUntilProcessExit();
        log.warn("Catalog operation still alive; ownership retained until process exit");
    }

    private final class Entry {
        final String id;
        final long createdAtMs = clock.millis();
        final long createdTick = ticks.getAsLong();
        CatalogOperationRequest request;
        State state = State.RESERVED;
        Long startedAtMs;
        Long completedAtMs;
        long finishedTick;
        JsonNode result;
        Integer errorStatus;
        String errorCode;
        Runnable task;
        Thread runner;
        Entry(String id) { this.id = id; }
    }

    private static final class LimitedResult extends ByteArrayOutputStream {
        boolean exceeded;
        private void requireSpace(int length) {
            if (length > MAX_RESULT_BYTES - count) {
                exceeded = true;
                throw new CatalogOperationFailure(500, "RESULT_TOO_LARGE");
            }
        }
        @Override public synchronized void write(int value) { requireSpace(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            requireSpace(length);
            super.write(bytes, offset, length);
        }
    }
}
