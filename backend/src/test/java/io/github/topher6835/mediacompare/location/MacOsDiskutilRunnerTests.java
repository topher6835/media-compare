package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.topher6835.mediacompare.process.BoundedProcessInterruptedException;

class MacOsDiskutilRunnerTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TERMINATION_GRACE = Duration.ofMillis(150);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(3);
    private static final int STDOUT_LIMIT = 4_096;
    private static final int STDERR_LIMIT = 1_024;

    @TempDir Path directory;

    @Test
    void invokesDiskutilArgumentsDirectlyAndCapturesBoundedOutput() {
        Path path = directory.resolve("Archive with spaces");
        MacOsDiskutilRunner.Output output = assertInstanceOf(
                MacOsDiskutilRunner.Output.class,
                runner(Scenario.SUCCESS, null, TIMEOUT, STDOUT_LIMIT, STDERR_LIMIT).inspect(path));

        assertEquals("info\n-plist\n" + path,
                new String(output.plist(), StandardCharsets.UTF_8));
        assertEquals(List.of("info", "-plist"), MacOsDiskutilRunner.infoArguments());
        assertNoReaderThreads();
    }

    @Test
    void reportsMissingExecutableAndNonzeroExit() {
        MacOsDiskutilRunner missing = runnerWithPrefix(
                List.of(directory.resolve("missing-diskutil").toString()),
                TIMEOUT, STDOUT_LIMIT, STDERR_LIMIT);
        MacOsDiskutilRunner.Failure unavailable = assertInstanceOf(
                MacOsDiskutilRunner.Failure.class, missing.inspect(directory));
        assertEquals(MacOsDiskutilRunner.FailureReason.EXECUTABLE_UNAVAILABLE, unavailable.reason());

        MacOsDiskutilRunner.Failure nonzero = assertInstanceOf(
                MacOsDiskutilRunner.Failure.class,
                runner(Scenario.NONZERO, null, TIMEOUT, STDOUT_LIMIT, STDERR_LIMIT).inspect(directory));
        assertEquals(MacOsDiskutilRunner.FailureReason.NONZERO_EXIT, nonzero.reason());
        assertEquals(7, nonzero.exitCode().orElseThrow());
    }

    @Test
    void enforcesTimeoutAndCleansUpChild() throws Exception {
        Path marker = directory.resolve("timeout.pid");
        MacOsDiskutilRunner.Failure failure = assertInstanceOf(
                MacOsDiskutilRunner.Failure.class,
                runner(Scenario.TIMEOUT, marker, Duration.ofMillis(250),
                        STDOUT_LIMIT, STDERR_LIMIT).inspect(directory));

        assertEquals(MacOsDiskutilRunner.FailureReason.TIMEOUT, failure.reason());
        assertProcessStopped(marker);
        assertNoReaderThreads();
    }

    @Test
    void enforcesStdoutAndStderrLimitsAndCleansUpChildren() throws Exception {
        Path stdoutMarker = directory.resolve("stdout.pid");
        MacOsDiskutilRunner.Failure stdout = assertInstanceOf(
                MacOsDiskutilRunner.Failure.class,
                runner(Scenario.STDOUT_OVERFLOW, stdoutMarker, Duration.ofSeconds(10),
                        128, STDERR_LIMIT).inspect(directory));
        assertEquals(MacOsDiskutilRunner.FailureReason.STDOUT_LIMIT_EXCEEDED, stdout.reason());
        assertProcessStopped(stdoutMarker);

        Path stderrMarker = directory.resolve("stderr.pid");
        MacOsDiskutilRunner.Failure stderr = assertInstanceOf(
                MacOsDiskutilRunner.Failure.class,
                runner(Scenario.STDERR_OVERFLOW, stderrMarker, Duration.ofSeconds(10),
                        STDOUT_LIMIT, 128).inspect(directory));
        assertEquals(MacOsDiskutilRunner.FailureReason.STDERR_LIMIT_EXCEEDED, stderr.reason());
        assertProcessStopped(stderrMarker);
        assertNoReaderThreads();
    }

    @Test
    void preservesInterruptionAndCleansUpChild() throws Exception {
        Path marker = directory.resolve("interrupt.pid");
        MacOsDiskutilRunner runner = runner(
                Scenario.TIMEOUT, marker, Duration.ofSeconds(30), STDOUT_LIMIT, STDERR_LIMIT);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                runner.inspect(directory);
            } catch (Throwable failure) {
                thrown.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "diskutil-interruption-test");

        caller.start();
        awaitMarker(marker);
        caller.interrupt();
        caller.join(5_000);

        assertFalse(caller.isAlive());
        assertInstanceOf(BoundedProcessInterruptedException.class, thrown.get());
        assertTrue(interrupted.get());
        assertProcessStopped(marker);
        assertNoReaderThreads();
    }

    private MacOsDiskutilRunner runner(
            Scenario scenario,
            Path marker,
            Duration timeout,
            int stdoutLimit,
            int stderrLimit) {
        return runnerWithPrefix(helperCommand(scenario, marker), timeout, stdoutLimit, stderrLimit);
    }

    private static MacOsDiskutilRunner runnerWithPrefix(
            List<String> prefix,
            Duration timeout,
            int stdoutLimit,
            int stderrLimit) {
        return MacOsDiskutilRunner.forTesting(
                prefix, timeout, TERMINATION_GRACE, CLEANUP_TIMEOUT,
                stdoutLimit, stderrLimit);
    }

    private static List<String> helperCommand(Scenario scenario, Path marker) {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        return List.of(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                HelperProcess.class.getName(),
                scenario.name(),
                marker == null ? "-" : marker.toString());
    }

    private static void awaitMarker(Path marker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(marker) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(marker), "helper process did not start");
    }

    private static void assertProcessStopped(Path marker) throws Exception {
        awaitMarker(marker);
        long pid = Long.parseLong(Files.readString(marker, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "helper process remains alive: " + pid);
    }

    private static void assertNoReaderThreads() {
        boolean alive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().startsWith("media-compare-diskutil-reader-"));
        assertFalse(alive, "diskutil reader thread remains alive");
    }

    enum Scenario {
        SUCCESS,
        NONZERO,
        TIMEOUT,
        STDOUT_OVERFLOW,
        STDERR_OVERFLOW
    }

    public static final class HelperProcess {
        private HelperProcess() {
        }

        public static void main(String[] args) throws Exception {
            Scenario scenario = Scenario.valueOf(args[0]);
            Path marker = "-".equals(args[1]) ? null : Path.of(args[1]);
            List<String> diskutilArguments = new ArrayList<>(Arrays.asList(args).subList(2, args.length));
            if (marker != null) {
                Files.writeString(marker, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            }
            switch (scenario) {
                case SUCCESS -> System.out.print(String.join("\n", diskutilArguments));
                case NONZERO -> System.exit(7);
                case TIMEOUT -> Thread.sleep(60_000);
                case STDOUT_OVERFLOW -> {
                    System.out.print("x".repeat(8_192));
                    System.out.flush();
                    Thread.sleep(60_000);
                }
                case STDERR_OVERFLOW -> {
                    System.err.print("x".repeat(8_192));
                    System.err.flush();
                    Thread.sleep(60_000);
                }
            }
        }
    }
}
