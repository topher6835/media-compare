package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.github.topher6835.mediacompare.management.CatalogStatus.State.*;
import static io.github.topher6835.mediacompare.management.CatalogStatus.Failure.*;

/** One child at a time. State changes never substitute for observing actual process exit. */
public final class CatalogProcessSupervisor implements AutoCloseable {
    private final List<String> commandPrefix;
    private final Duration startupTimeout;
    private final Duration shutdownTimeout;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-compare-catalog-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private Process child;
    private String token;
    private String catalogId;
    private CatalogStatus.State state = NOT_STARTED;
    private CatalogStatus.Failure failure;
    private Integer port;
    private Integer exitCode;
    private boolean stopRequested;
    private boolean closed;
    private ScheduledFuture<?> startupDeadline;
    private ScheduledFuture<?> shutdownDeadline;

    public CatalogProcessSupervisor() {
        this(CatalogChildCommand.currentApplication(), Duration.ofSeconds(60), Duration.ofSeconds(120));
    }

    // Package-local command/time budgets allow real helper JVM tests without changing production behavior.
    CatalogProcessSupervisor(List<String> commandPrefix, Duration startupTimeout, Duration shutdownTimeout) {
        if (startupTimeout.isNegative() || startupTimeout.isZero()
                || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("Catalog time budgets must be positive");
        }
        this.commandPrefix = List.copyOf(commandPrefix);
        this.startupTimeout = startupTimeout;
        this.shutdownTimeout = shutdownTimeout;
    }

    public synchronized CatalogStatus start(Path sessionRoot) {
        if (closed) throw new IllegalStateException("Catalog supervisor is closed");
        if (child != null && child.isAlive()) throw new IllegalStateException("Previous catalog process is still alive");
        cancelDeadlines();
        token = UUID.randomUUID().toString();
        catalogId = UUID.randomUUID().toString();
        failure = null;
        port = null;
        exitCode = null;
        stopRequested = false;
        var command = new ArrayList<>(commandPrefix);
        command.addAll(List.of("catalog-child", token, sessionRoot.toAbsolutePath().normalize().toString()));
        try {
            child = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            child = null;
            failure = PROCESS_START_FAILED;
            state = FAILED;
            return status();
        }
        state = CREATED;
        Process launched = child;
        String launchToken = token;
        child.onExit().thenRun(() -> exited(launched));
        startupDeadline = deadlines.schedule(() -> startupExpired(launched), startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        Thread output = new Thread(() -> readOutput(launched, launchToken), "media-compare-catalog-output");
        output.setDaemon(true);
        output.start();
        return status();
    }

    public synchronized CatalogStatus status() {
        boolean alive = child != null && child.isAlive();
        if (child != null && !alive && exitCode == null) exited(child);
        return new CatalogStatus(state, alive, port, exitCode, failure);
    }

    /** Browser generation is deliberately separate from the private child credential. */
    public synchronized String catalogId() { return catalogId; }

    record Route(String catalogId, int port, String credential) { }

    synchronized Route route(String expectedId) {
        CatalogStatus current = status();
        if (current.state() != RUNNING || !current.processAlive() || current.port() == null
                || expectedId == null || !expectedId.equals(catalogId)) return null;
        return new Route(catalogId, current.port(), token);
    }

    public synchronized CatalogStatus requestStop() {
        if (child == null || !child.isAlive()) return status();
        if (stopRequested) return status();
        stopRequested = true;
        port = null;
        if (startupDeadline != null) startupDeadline.cancel(false);
        if (failure == null) state = STOPPING;
        try (var control = child.outputWriter(StandardCharsets.UTF_8)) {
            control.write("STOP " + token + "\n");
            control.flush();
        } catch (IOException exception) {
            if (child.isAlive() && failure == null) { failure = CONTROL_FAILED; state = FAILED; }
        }
        Process stopping = child;
        shutdownDeadline = deadlines.schedule(() -> shutdownExpired(stopping), shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        return status();
    }

    private void readOutput(Process launched, String launchToken) {
        try (var output = launched.inputReader(StandardCharsets.UTF_8)) {
            String line;
            String prefix = "MC_CATALOG " + launchToken + " ";
            while ((line = output.readLine()) != null) {
                if (line.startsWith(prefix)) receive(launched, line.substring(prefix.length()));
                // Drain both output streams; arbitrary logs/paths never become management diagnostics.
            }
        } catch (IOException ignored) {
            // Exit observation and startup timeout remain authoritative if the pipe closes.
        }
    }

    private synchronized void receive(Process launched, String message) {
        if (child != launched) return;
        if (message.startsWith("FAILED ")) {
            CatalogStatus.Failure reported;
            try { reported = CatalogStatus.Failure.valueOf(message.substring(7)); }
            catch (IllegalArgumentException ignored) { return; }
            if (reported != INVALID_SESSION && reported != OWNERSHIP_FAILED
                    && reported != INITIALIZATION_FAILED && reported != SHUTDOWN_FAILED) return;
            if (failure == null || failure == STARTUP_FAILED || failure == UNEXPECTED_EXIT) failure = reported;
            state = FAILED;
            port = null;
            requestStop();
        } else if (!stopRequested && failure == null && launched.isAlive()) {
            if (message.equals("INITIALIZING") && state == CREATED) state = STARTING;
            if (message.startsWith("READY ") && state == STARTING) {
                int readyPort;
                try { readyPort = Integer.parseInt(message.substring(6)); }
                catch (NumberFormatException ignored) { return; }
                if (readyPort < 1 || readyPort > 65535) return;
                port = readyPort;
                state = RUNNING;
                startupDeadline.cancel(false);
            }
        }
    }

    private synchronized void startupExpired(Process launched) {
        if (child == launched && launched.isAlive() && (state == CREATED || state == STARTING)) {
            failure = STARTUP_TIMEOUT;
            state = FAILED;
            requestStop();
        }
    }

    private synchronized void shutdownExpired(Process launched) {
        if (child == launched && launched.isAlive()) {
            failure = SHUTDOWN_TIMEOUT;
            state = FAILED;
        }
    }

    private synchronized void exited(Process launched) {
        if (child != launched || launched.isAlive() || exitCode != null) return;
        exitCode = launched.exitValue();
        port = null;
        cancelDeadlines();
        if (failure == null && stopRequested && exitCode == 0) state = NOT_STARTED;
        else {
            if (failure == null) failure = state == CREATED || state == STARTING ? STARTUP_FAILED : UNEXPECTED_EXIT;
            state = FAILED;
        }
    }

    private void cancelDeadlines() {
        if (startupDeadline != null) startupDeadline.cancel(false);
        if (shutdownDeadline != null) shutdownDeadline.cancel(false);
    }

    @Override
    public void close() {
        Process stopping;
        synchronized (this) {
            closed = true;
            requestStop();
            stopping = child;
        }
        try {
            if (stopping != null && !stopping.waitFor(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                shutdownExpired(stopping);
                throw new IllegalStateException("Catalog shutdown timed out; process is still alive");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for catalog process exit", exception);
        } finally {
            deadlines.shutdown();
        }
    }
}
