package io.github.topher6835.mediacompare.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Runs one direct child process with bounded time, output, cleanup, and reader lifetime. */
public final class BoundedProcessExecutor {

    private static final AtomicInteger READER_SEQUENCE = new AtomicInteger();

    private final String readerThreadPrefix;

    public BoundedProcessExecutor(String readerThreadPrefix) {
        if (readerThreadPrefix == null || readerThreadPrefix.isBlank()) {
            throw new IllegalArgumentException("Reader thread prefix must not be blank");
        }
        this.readerThreadPrefix = readerThreadPrefix;
    }

    public Execution execute(
            List<String> command,
            Path redirectedInput,
            Duration timeout,
            Duration terminationGrace,
            Duration cleanupTimeout,
            int stdoutLimitBytes,
            int stderrLimitBytes) {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Process command must not be empty or blank");
        }
        requirePositive(timeout, "timeout");
        requirePositive(terminationGrace, "terminationGrace");
        requirePositive(cleanupTimeout, "cleanupTimeout");
        if (stdoutLimitBytes <= 0 || stderrLimitBytes <= 0) {
            throw new IllegalArgumentException("Process output limits must be positive");
        }

        ExecutorService readers = Executors.newFixedThreadPool(2, readerThreadFactory());
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE);
            if (redirectedInput != null) {
                builder.redirectInput(redirectedInput.toFile());
            }
            process = builder.start();
        } catch (IOException | RuntimeException exception) {
            readers.shutdownNow();
            return new ExecutionFailed(
                    ExecutionFailure.START_FAILED,
                    ("Could not start process: " + exception.getClass().getSimpleName())
                            .getBytes(StandardCharsets.UTF_8));
        }

        AtomicReference<ExecutionFailure> asynchronousFailure = new AtomicReference<>();
        CountDownLatch lifecycleEvent = new CountDownLatch(1);
        BoundedCapture stdout;
        BoundedCapture stderr;
        Future<byte[]> stdoutFuture;
        Future<byte[]> stderrFuture;
        try {
            if (redirectedInput == null) {
                process.getOutputStream().close();
            }
            stdout = new BoundedCapture(
                    process.getInputStream(), stdoutLimitBytes, StreamName.STDOUT,
                    failure -> signalFailure(asynchronousFailure, lifecycleEvent, failure));
            stderr = new BoundedCapture(
                    process.getErrorStream(), stderrLimitBytes, StreamName.STDERR,
                    failure -> signalFailure(asynchronousFailure, lifecycleEvent, failure));
            stdoutFuture = readers.submit(stdout);
            stderrFuture = readers.submit(stderr);
            readers.shutdown();
            process.onExit().thenRun(lifecycleEvent::countDown);
        } catch (IOException | RuntimeException exception) {
            Cleanup cleanup = terminateAndStop(
                    process, readers, terminationGrace, cleanupTimeout);
            propagateInterruption(cleanup.interrupted());
            return new ExecutionFailed(
                    cleanup.completed()
                            ? ExecutionFailure.SETUP_FAILED
                            : ExecutionFailure.CLEANUP_FAILED,
                    ("Could not set up process: " + exception.getClass().getSimpleName())
                            .getBytes(StandardCharsets.UTF_8));
        }

        try {
            if (!lifecycleEvent.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                Cleanup cleanup = terminateAndStop(
                        process, readers, terminationGrace, cleanupTimeout);
                propagateInterruption(cleanup.interrupted());
                return cleanup.completed()
                        ? new ExecutionFailed(ExecutionFailure.TIMEOUT, stderr.snapshot())
                        : new ExecutionFailed(ExecutionFailure.CLEANUP_FAILED, stderr.snapshot());
            }

            ExecutionFailure earlyFailure = asynchronousFailure.get();
            if (earlyFailure != null) {
                Cleanup cleanup = terminateAndStop(
                        process, readers, terminationGrace, cleanupTimeout);
                propagateInterruption(cleanup.interrupted());
                return cleanup.completed()
                        ? new ExecutionFailed(earlyFailure, stderr.snapshot())
                        : new ExecutionFailed(ExecutionFailure.CLEANUP_FAILED, stderr.snapshot());
            }

            CapturedStreams captured = awaitReaders(
                    stdoutFuture, stderrFuture, readers, cleanupTimeout, stdout, stderr);
            if (captured.interrupted()) {
                terminateAndStop(process, readers, terminationGrace, cleanupTimeout);
                Thread.currentThread().interrupt();
                throw new BoundedProcessInterruptedException();
            }
            if (!captured.completed()) {
                Cleanup cleanup = terminateAndStop(
                        process, readers, terminationGrace, cleanupTimeout);
                propagateInterruption(cleanup.interrupted());
                if (!cleanup.completed()) {
                    return new ExecutionFailed(ExecutionFailure.CLEANUP_FAILED, stderr.snapshot());
                }
                return new ExecutionFailed(
                        asynchronousFailure.get() == null
                                ? ExecutionFailure.STREAM_READ_FAILED
                                : asynchronousFailure.get(),
                        stderr.snapshot());
            }

            return new ExecutionFinished(
                    process.exitValue(), captured.stdout(), captured.stderr());
        } catch (InterruptedException exception) {
            terminateAndStop(process, readers, terminationGrace, cleanupTimeout);
            Thread.currentThread().interrupt();
            throw new BoundedProcessInterruptedException();
        }
    }

    private static CapturedStreams awaitReaders(
            Future<byte[]> stdoutFuture,
            Future<byte[]> stderrFuture,
            ExecutorService readers,
            Duration timeout,
            BoundedCapture stdout,
            BoundedCapture stderr) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            byte[] stdoutBytes = getBeforeDeadline(stdoutFuture, deadline);
            byte[] stderrBytes = getBeforeDeadline(stderrFuture, deadline);
            long remainingNanos = Math.max(0, deadline - System.nanoTime());
            if (!readers.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                return CapturedStreams.failed(stdout.snapshot(), stderr.snapshot());
            }
            return CapturedStreams.completed(stdoutBytes, stderrBytes);
        } catch (InterruptedException exception) {
            return CapturedStreams.interrupted(stdout.snapshot(), stderr.snapshot());
        } catch (ExecutionException | TimeoutException exception) {
            return CapturedStreams.failed(stdout.snapshot(), stderr.snapshot());
        }
    }

    private static byte[] getBeforeDeadline(Future<byte[]> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = Math.max(0, deadline - System.nanoTime());
        return future.get(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private static void signalFailure(
            AtomicReference<ExecutionFailure> asynchronousFailure,
            CountDownLatch lifecycleEvent,
            ExecutionFailure failure) {
        asynchronousFailure.compareAndSet(null, failure);
        lifecycleEvent.countDown();
    }

    private static Cleanup terminateAndStop(
            Process process,
            ExecutorService readers,
            Duration terminationGrace,
            Duration cleanupTimeout) {
        boolean interrupted = false;
        List<ProcessHandle> descendants = descendantsOf(process);
        descendants.forEach(BoundedProcessExecutor::destroy);
        process.destroy();

        WaitResult graceful = waitForProcess(process, terminationGrace);
        interrupted |= graceful.interrupted();
        if (!graceful.completed() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            descendants.forEach(BoundedProcessExecutor::destroyForcibly);
            process.destroyForcibly();
        }

        closeProcessStreams(process);
        readers.shutdownNow();

        WaitResult forced = waitForProcess(process, cleanupTimeout);
        interrupted |= forced.interrupted();
        WaitResult descendantsStopped = waitForHandles(descendants, cleanupTimeout);
        interrupted |= descendantsStopped.interrupted();
        WaitResult readersStopped = awaitExecutor(readers, cleanupTimeout);
        interrupted |= readersStopped.interrupted();
        boolean completed = forced.completed()
                && descendantsStopped.completed()
                && readersStopped.completed();
        return new Cleanup(completed, interrupted);
    }

    private static List<ProcessHandle> descendantsOf(Process process) {
        try {
            return new ArrayList<>(process.toHandle().descendants().toList());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static void destroy(ProcessHandle process) {
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // Descendant cleanup is best-effort; the directly owned process remains mandatory.
        }
    }

    private static void destroyForcibly(ProcessHandle process) {
        try {
            process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // Descendant cleanup is best-effort; the directly owned process remains mandatory.
        }
    }

    private static WaitResult waitForProcess(Process process, Duration timeout) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return new WaitResult(false, interrupted);
            }
            try {
                if (process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return new WaitResult(true, interrupted);
                }
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return new WaitResult(true, interrupted);
    }

    private static WaitResult waitForHandles(List<ProcessHandle> handles, Duration timeout) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive)) {
            if (System.nanoTime() >= deadline) {
                return new WaitResult(false, interrupted);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return new WaitResult(true, interrupted);
    }

    private static WaitResult awaitExecutor(ExecutorService executor, Duration timeout) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!executor.isTerminated()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return new WaitResult(false, interrupted);
            }
            try {
                if (executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return new WaitResult(true, interrupted);
                }
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return new WaitResult(true, interrupted);
    }

    private static void closeProcessStreams(Process process) {
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Process termination and bounded thread cleanup remain authoritative.
        }
    }

    private static void propagateInterruption(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new BoundedProcessInterruptedException();
        }
    }

    private ThreadFactory readerThreadFactory() {
        return task -> {
            Thread thread = new Thread(
                    task, readerThreadPrefix + READER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public sealed interface Execution permits ExecutionFinished, ExecutionFailed {
    }

    public record ExecutionFinished(int exitCode, byte[] stdout, byte[] stderr) implements Execution {
        public ExecutionFinished {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }

        @Override
        public byte[] stdout() {
            return stdout.clone();
        }

        @Override
        public byte[] stderr() {
            return stderr.clone();
        }
    }

    public record ExecutionFailed(ExecutionFailure reason, byte[] diagnostic) implements Execution {
        public ExecutionFailed {
            Objects.requireNonNull(reason, "reason");
            diagnostic = diagnostic.clone();
        }

        @Override
        public byte[] diagnostic() {
            return diagnostic.clone();
        }
    }

    public enum ExecutionFailure {
        START_FAILED,
        SETUP_FAILED,
        TIMEOUT,
        STDOUT_LIMIT_EXCEEDED,
        STDERR_LIMIT_EXCEEDED,
        STREAM_READ_FAILED,
        CLEANUP_FAILED
    }

    private enum StreamName {
        STDOUT,
        STDERR
    }

    private record Cleanup(boolean completed, boolean interrupted) {
    }

    private record WaitResult(boolean completed, boolean interrupted) {
    }

    private record CapturedStreams(
            boolean completed,
            boolean interrupted,
            byte[] stdout,
            byte[] stderr) {

        private static CapturedStreams completed(byte[] stdout, byte[] stderr) {
            return new CapturedStreams(true, false, stdout, stderr);
        }

        private static CapturedStreams failed(byte[] stdout, byte[] stderr) {
            return new CapturedStreams(false, false, stdout, stderr);
        }

        private static CapturedStreams interrupted(byte[] stdout, byte[] stderr) {
            return new CapturedStreams(false, true, stdout, stderr);
        }
    }

    private static final class BoundedCapture implements Callable<byte[]> {
        private final InputStream input;
        private final int limitBytes;
        private final StreamName stream;
        private final Consumer<ExecutionFailure> failureAction;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private BoundedCapture(
                InputStream input,
                int limitBytes,
                StreamName stream,
                Consumer<ExecutionFailure> failureAction) {
            this.input = input;
            this.limitBytes = limitBytes;
            this.stream = stream;
            this.failureAction = failureAction;
        }

        @Override
        public byte[] call() throws IOException {
            try (input) {
                byte[] chunk = new byte[8_192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    synchronized (buffer) {
                        if (buffer.size() + count > limitBytes) {
                            int remaining = limitBytes - buffer.size();
                            if (remaining > 0) {
                                buffer.write(chunk, 0, remaining);
                            }
                            failureAction.accept(stream == StreamName.STDOUT
                                    ? ExecutionFailure.STDOUT_LIMIT_EXCEEDED
                                    : ExecutionFailure.STDERR_LIMIT_EXCEEDED);
                            throw new OutputLimitException(stream);
                        }
                        buffer.write(chunk, 0, count);
                    }
                }
                return snapshot();
            } catch (OutputLimitException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                failureAction.accept(ExecutionFailure.STREAM_READ_FAILED);
                throw exception;
            }
        }

        private byte[] snapshot() {
            synchronized (buffer) {
                return buffer.toByteArray();
            }
        }
    }

    private static final class OutputLimitException extends IOException {
        private OutputLimitException(StreamName stream) {
            super(stream + " limit exceeded");
        }
    }
}
