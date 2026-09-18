package io.github.topher6835.mediacompare.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfprobeProcessRunnerTests {

    private static final Duration QUALIFICATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TERMINATION_GRACE = Duration.ofMillis(150);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(3);
    private static final int STDOUT_LIMIT = 4_096;
    private static final int STDERR_LIMIT = 1_024;
    private static final String PROBE_JSON =
            "{\"format\":{\"format_name\":\"matroska\"},\"streams\":[]}";

    @TempDir
    Path directory;

    @Test
    void runsQualifiedProcessAndCapturesSeparateOutputStreams() throws Exception {
        Path input = write("movie.bin", "media bytes");

        FfprobeProcessResult.ProbeOutput output = assertInstanceOf(
                FfprobeProcessResult.ProbeOutput.class,
                runner(Scenario.SUCCESS).probe(input));

        assertEquals(PROBE_JSON, output.json());
        assertEquals("helper warning", output.diagnostic());
        assertNoReaderThreads();
    }

    @Test
    void reportsNonzeroExitAsProbeFailureWithoutParsingStderr() throws Exception {
        FfprobeProcessResult.ProbeFailure failure = assertInstanceOf(
                FfprobeProcessResult.ProbeFailure.class,
                runner(Scenario.NONZERO).probe(write("bad.bin", "not media")));

        assertEquals(FfprobeProcessResult.ProbeFailureReason.NONZERO_EXIT, failure.reason());
        assertEquals(7, failure.exitCode().orElseThrow());
        assertEquals("fixture probe failure", failure.diagnostic());
    }

    @Test
    void reportsMissingExecutableWithoutFallingBackToPath() throws Exception {
        Path missingExecutable = directory.resolve("explicitly-missing-ffprobe");
        FfprobeProcessRunner runner = new FfprobeProcessRunner(missingExecutable.toString());

        FfprobeProcessResult.InfrastructureFailure failure = assertInstanceOf(
                FfprobeProcessResult.InfrastructureFailure.class,
                runner.probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.InfrastructureFailureReason.EXECUTABLE_UNAVAILABLE,
                failure.reason());
    }

    @Test
    void acceptsAbsentExecutableOverrideForPathLookup() {
        new FfprobeProcessRunner(null);
    }

    @Test
    void requiresExplicitExecutableOverrideToBeAbsolute() {
        assertThrows(IllegalArgumentException.class, () -> new FfprobeProcessRunner("ffprobe"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FfprobeProcessRunner(Path.of("tools", "ffprobe").toString()));
    }

    @Test
    void rejectsBlankOrPaddedExecutableOverride() {
        assertThrows(IllegalArgumentException.class, () -> new FfprobeProcessRunner(""));
        assertThrows(IllegalArgumentException.class, () -> new FfprobeProcessRunner("   "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FfprobeProcessRunner(directory.resolve("ffprobe") + " "));
    }

    @Test
    void reportsNonzeroQualification() throws Exception {
        FfprobeProcessResult.InfrastructureFailure failure = assertInstanceOf(
                FfprobeProcessResult.InfrastructureFailure.class,
                runner(Scenario.QUALIFICATION_NONZERO).probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                failure.reason());
    }

    @Test
    void reportsQualificationTimeout() throws Exception {
        FfprobeProcessRunner runner = testRunner(
                helperCommand(Scenario.QUALIFICATION_TIMEOUT, null),
                Duration.ofMillis(150), PROBE_TIMEOUT, STDOUT_LIMIT, STDERR_LIMIT);

        FfprobeProcessResult.InfrastructureFailure failure = assertInstanceOf(
                FfprobeProcessResult.InfrastructureFailure.class,
                runner.probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_TIMEOUT,
                failure.reason());
    }

    @Test
    void reportsQualificationOutputLimitAsInfrastructureFailure() throws Exception {
        FfprobeProcessResult.InfrastructureFailure failure = assertInstanceOf(
                FfprobeProcessResult.InfrastructureFailure.class,
                runner(Scenario.QUALIFICATION_STDOUT_OVERFLOW).probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                failure.reason());
        assertNoReaderThreads();
    }

    @Test
    void requiresFdInputProtocolDuringQualification() throws Exception {
        FfprobeProcessResult.InfrastructureFailure failure = assertInstanceOf(
                FfprobeProcessResult.InfrastructureFailure.class,
                runner(Scenario.MISSING_FD_PROTOCOL).probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.InfrastructureFailureReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                failure.reason());
    }

    @Test
    void requiresMovExternalReferenceControlsDuringQualification() throws Exception {
        FfprobeProcessResult.InfrastructureFailure failure = assertInstanceOf(
                FfprobeProcessResult.InfrastructureFailure.class,
                runner(Scenario.MISSING_MOV_OPTIONS).probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.InfrastructureFailureReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                failure.reason());
    }

    @Test
    void enforcesProbeTimeoutAndTerminatesChild() throws Exception {
        Path marker = directory.resolve("timeout.pid");
        FfprobeProcessRunner runner = testRunner(
                helperCommand(Scenario.TIMEOUT, marker),
                QUALIFICATION_TIMEOUT, Duration.ofMillis(250), STDOUT_LIMIT, STDERR_LIMIT);

        FfprobeProcessResult.ProbeFailure failure = assertInstanceOf(
                FfprobeProcessResult.ProbeFailure.class,
                runner.probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.ProbeFailureReason.PROCESS_TIMEOUT,
                failure.reason());
        assertTrue(failure.exitCode().isEmpty());
        assertProcessStopped(marker);
        assertNoReaderThreads();
    }

    @Test
    void forciblyTerminatesChildThatDoesNotFinishGracefully() throws Exception {
        Path marker = directory.resolve("forced.pid");
        FfprobeProcessRunner runner = testRunner(
                helperCommand(Scenario.FORCED_TERMINATION, marker),
                QUALIFICATION_TIMEOUT, Duration.ofMillis(250), STDOUT_LIMIT, STDERR_LIMIT);

        FfprobeProcessResult.ProbeFailure failure = assertInstanceOf(
                FfprobeProcessResult.ProbeFailure.class,
                runner.probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.ProbeFailureReason.PROCESS_TIMEOUT,
                failure.reason());
        assertTrue(failure.exitCode().isEmpty());
        assertProcessStopped(marker);
    }

    @Test
    void terminatesProcessWhenStdoutLimitIsExceeded() throws Exception {
        Path marker = directory.resolve("stdout-overflow.pid");
        Duration normalTimeout = Duration.ofSeconds(10);
        FfprobeProcessRunner runner = testRunner(
                helperCommand(Scenario.STDOUT_OVERFLOW, marker),
                QUALIFICATION_TIMEOUT, normalTimeout, 128, STDERR_LIMIT);

        long started = System.nanoTime();
        FfprobeProcessResult.ProbeFailure failure = assertInstanceOf(
                FfprobeProcessResult.ProbeFailure.class,
                runner.probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.ProbeFailureReason.STDOUT_LIMIT_EXCEEDED,
                failure.reason());
        assertTrue(failure.exitCode().isEmpty());
        assertCompletedPromptly(started, normalTimeout);
        assertProcessStopped(marker);
        assertNoReaderThreads();
    }

    @Test
    void terminatesProcessWhenStderrLimitIsExceeded() throws Exception {
        Path marker = directory.resolve("stderr-overflow.pid");
        Duration normalTimeout = Duration.ofSeconds(10);
        FfprobeProcessRunner runner = testRunner(
                helperCommand(Scenario.STDERR_OVERFLOW, marker),
                QUALIFICATION_TIMEOUT, normalTimeout, STDOUT_LIMIT, 128);

        long started = System.nanoTime();
        FfprobeProcessResult.ProbeFailure failure = assertInstanceOf(
                FfprobeProcessResult.ProbeFailure.class,
                runner.probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.ProbeFailureReason.STDERR_LIMIT_EXCEEDED,
                failure.reason());
        assertTrue(failure.exitCode().isEmpty());
        assertCompletedPromptly(started, normalTimeout);
        assertProcessStopped(marker);
        assertNoReaderThreads();
    }

    @Test
    void rejectsMalformedUtf8Stdout() throws Exception {
        FfprobeProcessResult.ProbeFailure failure = assertInstanceOf(
                FfprobeProcessResult.ProbeFailure.class,
                runner(Scenario.INVALID_UTF8).probe(write("movie.bin", "media")));

        assertEquals(
                FfprobeProcessResult.ProbeFailureReason.INVALID_UTF8_OUTPUT,
                failure.reason());
        assertEquals(0, failure.exitCode().orElseThrow());
    }

    @Test
    void redirectsTheRegularFileToChildStdin() throws Exception {
        byte[] content = "redirected bytes \u2603".getBytes(StandardCharsets.UTF_8);
        Path input = directory.resolve("input.bin");
        Files.write(input, content);

        FfprobeProcessResult.ProbeOutput output = assertInstanceOf(
                FfprobeProcessResult.ProbeOutput.class,
                runner(Scenario.ECHO_STDIN).probe(input));

        assertEquals(Base64.getEncoder().encodeToString(content), output.json());
    }

    @Test
    void redirectedStdinIsSeekableAtTheJavaProcessBoundary() throws Exception {
        Path input = directory.resolve("seekable.bin");
        Files.write(input, new byte[] { 10, 20, 30, 40 });

        FfprobeProcessResult.ProbeOutput output = assertInstanceOf(
                FfprobeProcessResult.ProbeOutput.class,
                runner(Scenario.SEEK_STDIN).probe(input));

        assertEquals("4:30", output.json());
    }

    @Test
    void specialFilenamesCannotAlterTheArgumentVector() throws Exception {
        FfprobeProcessRunner runner = runner(Scenario.ECHO_STDIN);
        for (String filename : List.of(
                "media with spaces.bin",
                "m\u00e9dia-\u96ea.bin",
                "media;not-a-command.bin",
                "frame-%03d.bin")) {
            byte[] content = filename.getBytes(StandardCharsets.UTF_8);
            Path input = directory.resolve(filename);
            Files.write(input, content);

            FfprobeProcessResult.ProbeOutput output = assertInstanceOf(
                    FfprobeProcessResult.ProbeOutput.class, runner.probe(input));
            assertEquals(Base64.getEncoder().encodeToString(content), output.json());
            assertFalse(FfprobeProcessRunner.probeArguments().contains(input.toString()));
        }
    }

    @Test
    void usesFixedFdOnlyArgumentVector() {
        assertEquals(List.of(
                "-v", "error",
                "-protocol_whitelist", "fd",
                "-enable_drefs", "0",
                "-use_absolute_path", "0",
                "-show_entries",
                "format=format_name,duration:format_tags=major_brand,compatible_brands:"
                        + "stream=index,codec_type,codec_name,width,height:"
                        + "stream_disposition=default,attached_pic,timed_thumbnails,still_image",
                "-of", "json",
                "-i", "fd:"), FfprobeProcessRunner.probeArguments());
        assertFalse(FfprobeProcessRunner.probeArguments().contains("pipe:0"));
        assertFalse(FfprobeProcessRunner.probeArguments().contains("file"));
    }

    @Test
    void preservesInterruptStatusAndCleansUpChild() throws Exception {
        Path marker = directory.resolve("interrupted.pid");
        FfprobeProcessRunner runner = testRunner(
                helperCommand(Scenario.TIMEOUT, marker),
                QUALIFICATION_TIMEOUT, Duration.ofSeconds(30), STDOUT_LIMIT, STDERR_LIMIT);
        Path input = write("movie.bin", "media");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                runner.probe(input);
            } catch (Throwable failure) {
                thrown.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "ffprobe-interruption-test");

        caller.start();
        awaitMarker(marker);
        caller.interrupt();
        caller.join(5_000);

        assertFalse(caller.isAlive());
        assertInstanceOf(FfprobeRunnerInterruptedException.class, thrown.get());
        assertTrue(interrupted.get());
        assertProcessStopped(marker);
        assertNoReaderThreads();
    }

    @Test
    void exposesOperationalDefaultsWithoutChangingAnalyzerIdentity() {
        assertEquals(Duration.ofSeconds(5), FfprobeProcessRunner.DEFAULT_QUALIFICATION_TIMEOUT);
        assertEquals(Duration.ofSeconds(30), FfprobeProcessRunner.DEFAULT_PROBE_TIMEOUT);
        assertEquals(4 * 1_024 * 1_024, FfprobeProcessRunner.DEFAULT_STDOUT_LIMIT_BYTES);
        assertEquals(256 * 1_024, FfprobeProcessRunner.DEFAULT_STDERR_LIMIT_BYTES);
        assertEquals("builtin.ffprobe", FfprobeMediaMetadataDefinition.ANALYZER_ID);
        assertEquals("{}", FfprobeMediaMetadataDefinition.CONFIGURATION_JSON);
    }

    private FfprobeProcessRunner runner(Scenario scenario) {
        return testRunner(
                helperCommand(scenario, null),
                QUALIFICATION_TIMEOUT, PROBE_TIMEOUT, STDOUT_LIMIT, STDERR_LIMIT);
    }

    private FfprobeProcessRunner testRunner(
            List<String> command,
            Duration qualificationTimeout,
            Duration probeTimeout,
            int stdoutLimit,
            int stderrLimit) {
        return FfprobeProcessRunner.forTesting(
                command,
                qualificationTimeout,
                probeTimeout,
                TERMINATION_GRACE,
                CLEANUP_TIMEOUT,
                stdoutLimit,
                stderrLimit);
    }

    private static List<String> helperCommand(Scenario scenario, Path marker) {
        String java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString();
        return List.of(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                HelperProcess.class.getName(),
                scenario.name(),
                marker == null ? "-" : marker.toString());
    }

    private Path write(String filename, String content) throws IOException {
        Path file = directory.resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
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
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        assertFalse(alive, "helper process remains alive: " + pid);
    }

    private static void assertNoReaderThreads() {
        boolean readerAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().startsWith("media-compare-ffprobe-reader-"));
        assertFalse(readerAlive, "ffprobe reader thread remains alive");
    }

    private static void assertCompletedPromptly(long startedNanos, Duration normalTimeout) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
        assertTrue(
                elapsed.compareTo(normalTimeout.dividedBy(2)) < 0,
                "overflow cleanup waited for the normal probe timeout: " + elapsed);
    }

    enum Scenario {
        SUCCESS,
        NONZERO,
        QUALIFICATION_NONZERO,
        QUALIFICATION_TIMEOUT,
        QUALIFICATION_STDOUT_OVERFLOW,
        MISSING_FD_PROTOCOL,
        MISSING_MOV_OPTIONS,
        TIMEOUT,
        FORCED_TERMINATION,
        STDOUT_OVERFLOW,
        STDERR_OVERFLOW,
        INVALID_UTF8,
        ECHO_STDIN,
        SEEK_STDIN
    }

    public static final class HelperProcess {

        private HelperProcess() {
        }

        public static void main(String[] args) throws Exception {
            Scenario scenario = Scenario.valueOf(args[0]);
            Path marker = "-".equals(args[1]) ? null : Path.of(args[1]);
            List<String> ffprobeArguments = new ArrayList<>(
                    Arrays.asList(args).subList(2, args.length));

            if (ffprobeArguments.equals(List.of("-version"))) {
                runVersion(scenario);
                return;
            }
            if (ffprobeArguments.equals(List.of("-v", "error", "-protocols"))) {
                runProtocols(scenario);
                return;
            }
            if (ffprobeArguments.equals(List.of(
                    "-v", "error", "-hide_banner", "-h", "demuxer=mov"))) {
                runMovHelp(scenario);
                return;
            }

            if (marker != null) {
                Files.writeString(marker, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            }
            switch (scenario) {
                case SUCCESS -> {
                    System.out.print(PROBE_JSON);
                    System.err.print("helper warning");
                }
                case NONZERO -> {
                    System.err.print("fixture probe failure");
                    System.exit(7);
                }
                case TIMEOUT -> Thread.sleep(60_000);
                case FORCED_TERMINATION -> {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> sleep(60_000)));
                    Thread.sleep(60_000);
                }
                case STDOUT_OVERFLOW -> {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> sleep(60_000)));
                    System.out.write("x".repeat(8_192).getBytes(StandardCharsets.UTF_8));
                    System.out.flush();
                    Thread.sleep(60_000);
                }
                case STDERR_OVERFLOW -> {
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> sleep(60_000)));
                    System.err.write("x".repeat(8_192).getBytes(StandardCharsets.UTF_8));
                    System.err.flush();
                    Thread.sleep(60_000);
                }
                case INVALID_UTF8 -> System.out.write(new byte[] { (byte) 0xc3, (byte) 0x28 });
                case ECHO_STDIN -> System.out.print(
                        Base64.getEncoder().encodeToString(System.in.readAllBytes()));
                case SEEK_STDIN -> seekStdin();
                default -> throw new IllegalStateException("Unexpected probe scenario: " + scenario);
            }
        }

        private static void runVersion(Scenario scenario) throws Exception {
            if (scenario == Scenario.QUALIFICATION_NONZERO) {
                System.err.print("qualification failed");
                System.exit(9);
            }
            if (scenario == Scenario.QUALIFICATION_TIMEOUT) {
                Thread.sleep(60_000);
            }
            if (scenario == Scenario.QUALIFICATION_STDOUT_OVERFLOW) {
                System.out.write("x".repeat(8_192).getBytes(StandardCharsets.UTF_8));
                System.out.flush();
                Thread.sleep(60_000);
                return;
            }
            System.out.println("ffprobe version helper");
        }

        private static void runProtocols(Scenario scenario) {
            System.out.println("Supported file protocols:");
            System.out.println("Input:");
            if (scenario != Scenario.MISSING_FD_PROTOCOL) {
                System.out.println("  fd");
            }
            System.out.println("Output:");
            System.out.println("  fd");
        }

        private static void runMovHelp(Scenario scenario) {
            System.out.println("mov AVOptions:");
            if (scenario != Scenario.MISSING_MOV_OPTIONS) {
                System.out.println("  -use_absolute_path <boolean>");
                System.out.println("  -enable_drefs <boolean>");
            }
        }

        private static void seekStdin() throws Exception {
            try (var input = new FileInputStream(FileDescriptor.in)) {
                var channel = input.getChannel();
                long size = channel.size();
                channel.position(2);
                int value = input.read();
                System.out.print(size + ":" + value);
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
