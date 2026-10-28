package io.github.topher6835.mediacompare.management;

import static io.github.topher6835.mediacompare.management.SessionCreationOperation.Kind.*;
import static io.github.topher6835.mediacompare.management.SessionCreationOperation.State.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.github.topher6835.mediacompare.session.Session;
import io.github.topher6835.mediacompare.session.SessionCreationService;

/** Bounded, management-local creation/retry history. Reservation alone can never perform work. */
final class SessionCreationOperations {
    record Input(String parentPath, String name) {
        Input {
            if (parentPath == null || name == null || parentPath.length() > 16_384 || name.length() > 1024) {
                throw new Failure(400, "INVALID_INPUT", "Supply a bounded parent path and Session name.");
            }
        }
    }

    static final class Failure extends RuntimeException {
        final int status;
        final String code;
        Failure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }

    @FunctionalInterface
    interface Creator { SessionCreationService.CreatedSession create(Input input) throws IOException; }

    private static final int MAX_RECORDS = 64;
    private final String runtimeId = UUID.randomUUID().toString();
    private final Map<String, SessionCreationOperation> entries = new LinkedHashMap<>();
    private final CatalogProcessSupervisor catalog;
    private final SessionLifecycleAdmission admission;
    private final Creator creator;
    private final LongSupplier ticks;
    private final Duration retention;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "media-compare-session-creation");
                thread.setDaemon(false);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-compare-session-creation-status");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closing;

    SessionCreationOperations(CatalogProcessSupervisor catalog, SessionLifecycleAdmission admission) {
        this(catalog, admission, SessionCreationActions::create, System::nanoTime, Duration.ofMinutes(10));
    }

    SessionCreationOperations(CatalogProcessSupervisor catalog, SessionLifecycleAdmission admission,
            Creator creator, LongSupplier ticks, Duration retention) {
        this.catalog = catalog;
        this.admission = admission;
        this.creator = creator;
        this.ticks = ticks;
        this.retention = retention;
        monitor.scheduleWithFixedDelay(this::refresh, 100, 100, TimeUnit.MILLISECONDS);
    }

    synchronized Map<String, Object> reserve() {
        requireOpen();
        refresh();
        return snapshot(add(CREATE, null, null), false);
    }

    synchronized Map<String, Object> reserveRetry(String creationId, String failedAttemptId) {
        requireOpen();
        refresh();
        var root = requireEntry(creationId);
        if (root.kind != CREATE || root.path == null || !root.latestAttemptId.equals(failedAttemptId)) {
            throw new Failure(409, "RETRY_NOT_AVAILABLE", "Retry must identify the latest failed opening of a created Session.");
        }
        var failed = requireEntry(failedAttemptId);
        if (failed.state != OPEN_FAILED) throw new Failure(409, "RETRY_NOT_AVAILABLE", "The latest opening has not failed.");
        if (catalog.status().processAlive()) throw new Failure(409, "RETRY_CHILD_ALIVE", "Wait for the previous catalog to fully exit before retrying.");
        var retry = add(RETRY_OPEN, root.id, failed.id);
        retry.path = root.path;
        retry.name = root.name;
        return snapshot(retry, false);
    }

    synchronized Map<String, Object> submit(String id, Input input) {
        requireOpen();
        refresh();
        var entry = requireEntry(id);
        if (entry.kind != CREATE) throw new Failure(409, "OPERATION_KIND_CHANGED", "This handle only retries opening an existing Session.");
        if (entry.input != null && !entry.input.equals(input)) throw new Failure(409, "OPERATION_INPUT_CHANGED", "The operation's creation input cannot change.");
        entry.input = input;
        if (entry.state == RESERVED) execute(entry);
        return snapshot(entry, false);
    }

    synchronized Map<String, Object> submitRetry(String id) {
        requireOpen();
        refresh();
        var entry = requireEntry(id);
        if (entry.kind != RETRY_OPEN) throw new Failure(409, "OPERATION_KIND_CHANGED", "Submit creation input to this handle.");
        if (entry.state != RESERVED) return snapshot(entry, false);
        var root = requireEntry(entry.creationId);
        if (!root.latestAttemptId.equals(entry.previousAttemptId)) {
            throw new Failure(409, "RETRY_SUPERSEDED", "A newer opening attempt exists. Inspect its result before deliberately retrying.");
        }
        execute(entry);
        root.latestAttemptId = entry.id;
        return snapshot(entry, false);
    }

    private void execute(SessionCreationOperation entry) {
        entry.lease = admission.acquire();
        entry.state = entry.kind == CREATE ? CREATING : OPENING;
        try { worker.execute(() -> run(entry)); }
        catch (java.util.concurrent.RejectedExecutionException stopped) {
            entry.lease.close();
            entry.lease = null;
            entry.state = RESERVED;
            throw new Failure(503, "MANAGEMENT_STOPPING", "Management cannot execute this operation. Poll before retrying.");
        }
    }

    synchronized Map<String, Object> status(String id) {
        refresh();
        return snapshot(requireEntry(id), false);
    }

    synchronized Map<String, Object> list() {
        refresh();
        return Map.of("runtimeId", runtimeId, "stopping", closing,
                "operations", entries.values().stream().map(entry -> snapshot(entry, true)).toList());
    }

    private void run(SessionCreationOperation entry) {
        try {
            synchronized (this) {
                entry.runner = Thread.currentThread();
                if (closing) {
                    finish(entry, entry.kind == CREATE ? REJECTED : OPEN_FAILED, "MANAGEMENT_STOPPING",
                            "Management stopped before execution. Inspect the retained operation before retrying.");
                    return;
                }
            }
            if (entry.kind == CREATE) {
                var created = creator.create(entry.input); // No management/supervisor lock is held during IO.
                synchronized (this) {
                    entry.path = created.path().toString();
                    entry.name = created.name();
                    entry.directoryCreated = true;
                    entry.state = OPENING;
                }
            }
            Session session = Session.open(Path.of(entry.path)); // Retry opens exactly the retained path.
            synchronized (this) {
                if (closing || Thread.currentThread().isInterrupted()) {
                    finish(entry, OPEN_FAILED, "MANAGEMENT_STOPPING",
                            "The Session was created but not opened. Inspect it or open it manually after restart.");
                    return;
                }
                var launch = entry.lease.start(session.root());
                entry.catalogId = launch.catalogId();
                observeLaunch(entry);
            }
        } catch (SessionCreationService.CreationFailure failure) {
            synchronized (this) {
                entry.attemptedPath = failure.attemptedPath().toString();
                entry.directoryCreated = failure.directoryCreated();
                finish(entry, failure.mutationAttempted() ? CREATION_UNCERTAIN : REJECTED,
                        failure.mutationAttempted() ? "CREATION_UNCERTAIN" : "CREATION_REJECTED",
                        failure.getMessage()); // Task 1's bounded message excludes underlying provider diagnostics.
            }
        } catch (Failure failure) {
            synchronized (this) { finish(entry, entry.path == null ? REJECTED : OPEN_FAILED, failure.code, failure.getMessage()); }
        } catch (IllegalArgumentException invalid) {
            synchronized (this) { finish(entry, entry.path == null ? REJECTED : OPEN_FAILED, "INVALID_INPUT", "Enter an absolute parent path and a valid portable Session name."); }
        } catch (Throwable failure) {
            synchronized (this) {
                finish(entry, entry.path == null ? CREATION_UNCERTAIN : OPEN_FAILED,
                        entry.path == null ? "CREATION_UNCERTAIN" : "CATALOG_OPEN_FAILED",
                        "The operation could not complete. Inspect the requested parent or retained Session; do not automatically create another.");
            }
            if (failure instanceof Error error) throw error;
        } finally {
            synchronized (this) {
                entry.runner = null;
                entry.lease.close();
                entry.lease = null;
            }
            Thread.interrupted(); // An interruption cannot leak to another operation on the worker.
        }
    }

    private void observeLaunch(SessionCreationOperation entry) {
        if (entry.state != OPENING || entry.catalogId == null) return;
        synchronized (catalog) {
            var status = catalog.status();
            if (!entry.catalogId.equals(catalog.catalogId())) {
                finish(entry, OPEN_FAILED, "CATALOG_LAUNCH_CHANGED", "The catalog launch changed. Inspect the current catalog before retrying.");
            } else if (status.state() == CatalogStatus.State.RUNNING && status.processAlive()) {
                finish(entry, RUNNING, null, null);
            } else if (status.state() == CatalogStatus.State.FAILED || !status.processAlive()) {
                entry.openingFailureCode = status.failure() == null ? null : status.failure().name();
                finish(entry, OPEN_FAILED, "CATALOG_OPEN_FAILED", "The Session was created but catalog opening failed. Wait for actual exit, then explicitly retry opening or open it manually.");
            }
        }
    }

    private void finish(SessionCreationOperation entry, SessionCreationOperation.State state, String code, String message) {
        entry.state = state;
        entry.errorCode = code;
        entry.message = message;
        entry.completedAtMs = System.currentTimeMillis();
        entry.completedTick = ticks.getAsLong();
    }

    private SessionCreationOperation add(SessionCreationOperation.Kind kind, String creationId, String previousId) {
        if (entries.size() >= MAX_RECORDS) throw new Failure(429, "OPERATION_STORE_FULL", "Creation history is full. Recover retained operations or wait for unused reservations to expire.");
        var entry = new SessionCreationOperation(runtimeId + "." + UUID.randomUUID(), kind, creationId, previousId, ticks.getAsLong());
        entries.put(entry.id, entry);
        return entry;
    }

    private SessionCreationOperation requireEntry(String id) {
        if (id == null || !id.startsWith(runtimeId + ".")) throw new Failure(409, "MANAGEMENT_RUNTIME_CHANGED", "This management runtime does not own the operation. Inspect any possibly created Session manually.");
        var entry = entries.get(id);
        if (entry == null) throw new Failure(410, "OPERATION_EXPIRED", "The operation is unknown or expired. Inspect the parent before deliberately creating again.");
        return entry;
    }

    private synchronized void refresh() {
        entries.values().forEach(this::observeLaunch);
        // Retry records expire first so they cannot leave an orphaned root outside the record bound.
        entries.values().removeIf(entry -> entry.kind == RETRY_OPEN && expired(entry));
        entries.values().removeIf(entry -> entry.kind == CREATE && expired(entry)
                && entries.values().stream().noneMatch(other -> other != entry && other.creationId.equals(entry.id)));
    }

    private boolean expired(SessionCreationOperation entry) {
        if (entry.lease != null || entry.state == CREATING || entry.state == OPENING) return false;
        synchronized (catalog) {
            if (entry.catalogId != null && entry.catalogId.equals(catalog.catalogId()) && catalog.status().processAlive()) return false;
        }
        long since = entry.completedAtMs == null ? entry.createdTick : entry.completedTick;
        return ticks.getAsLong() - since >= retention.toNanos();
    }

    private Map<String, Object> snapshot(SessionCreationOperation entry, boolean summary) {
        var result = new LinkedHashMap<String, Object>();
        result.put("operationId", entry.id);
        result.put("kind", entry.kind.name());
        result.put("state", entry.state.name());
        result.put("creationId", entry.creationId);
        result.put("latestAttemptId", entry.latestAttemptId);
        result.put("createdAtMs", entry.createdAtMs);
        result.put("completedAtMs", entry.completedAtMs);
        result.put("sessionCreated", entry.path != null);
        result.put("executionActive", entry.lease != null);
        result.put("catalogId", entry.catalogId);
        result.put("errorCode", entry.errorCode);
        if (summary) return result;
        result.put("stopping", closing);
        result.put("parentPath", entry.input == null ? null : entry.input.parentPath());
        result.put("requestedName", entry.input == null ? null : entry.input.name());
        result.put("name", entry.name);
        result.put("path", entry.path);
        result.put("attemptedPath", entry.attemptedPath);
        result.put("directoryCreated", entry.directoryCreated);
        result.put("previousAttemptId", entry.previousAttemptId);
        result.put("message", entry.message);
        result.put("openingFailureCode", entry.openingFailureCode);
        synchronized (catalog) {
            boolean matches = entry.catalogId != null && entry.catalogId.equals(catalog.catalogId());
            var status = catalog.status();
            result.put("catalogMatchesCurrent", matches);
            result.put("catalogState", matches ? status.state().name() : null);
            result.put("processAlive", matches ? status.processAlive() : null);
            result.put("catalogFailureCode", matches && status.failure() != null ? status.failure().name() : null);
        }
        return result;
    }

    private void requireOpen() {
        if (closing) throw new Failure(503, "MANAGEMENT_STOPPING", "Management is stopping. Retained operations remain readable.");
    }

    void shutdown(Duration budget) {
        synchronized (this) {
            closing = true;
            admission.stopAccepting();
            worker.shutdown(); // An accepted task must run its finally block and release admission.
            entries.values().forEach(entry -> { if (entry.runner != null) entry.runner.interrupt(); });
        }
        try {
            if (!worker.awaitTermination(budget.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Session creation is still active; management remains available for recovery");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Session creation", interrupted);
        }
        monitor.shutdownNow();
    }
}
