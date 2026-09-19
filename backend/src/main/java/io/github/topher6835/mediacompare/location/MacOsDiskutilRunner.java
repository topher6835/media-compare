package io.github.topher6835.mediacompare.location;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import io.github.topher6835.mediacompare.process.BoundedProcessExecutor;

/** Direct, shell-free, bounded execution of {@code diskutil info -plist}. */
public final class MacOsDiskutilRunner {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    static final int DEFAULT_STDOUT_LIMIT_BYTES = 1_024 * 1_024;
    static final int DEFAULT_STDERR_LIMIT_BYTES = 64 * 1_024;
    private static final Duration DEFAULT_TERMINATION_GRACE = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CLEANUP_TIMEOUT = Duration.ofSeconds(2);
    private static final List<String> INFO_ARGUMENTS = List.of("info", "-plist");

    private final BoundedProcessExecutor executor;
    private final Settings settings;

    public MacOsDiskutilRunner() {
        this(new BoundedProcessExecutor("media-compare-diskutil-reader-"),
                Settings.defaults());
    }

    private MacOsDiskutilRunner(BoundedProcessExecutor executor, Settings settings) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public Result inspect(Path exactPath) {
        Objects.requireNonNull(exactPath, "exactPath");
        if (!exactPath.isAbsolute()) {
            throw new IllegalArgumentException("diskutil inspection path must be absolute");
        }
        BoundedProcessExecutor.Execution execution = executor.execute(
                command(exactPath),
                null,
                settings.timeout(),
                settings.terminationGrace(),
                settings.cleanupTimeout(),
                settings.stdoutLimitBytes(),
                settings.stderrLimitBytes());
        if (execution instanceof BoundedProcessExecutor.ExecutionFailed failed) {
            FailureReason reason = switch (failed.reason()) {
                case START_FAILED -> FailureReason.EXECUTABLE_UNAVAILABLE;
                case TIMEOUT -> FailureReason.TIMEOUT;
                case STDOUT_LIMIT_EXCEEDED -> FailureReason.STDOUT_LIMIT_EXCEEDED;
                case STDERR_LIMIT_EXCEEDED -> FailureReason.STDERR_LIMIT_EXCEEDED;
                case SETUP_FAILED, STREAM_READ_FAILED, CLEANUP_FAILED -> FailureReason.PROCESS_ERROR;
            };
            return new Failure(reason, OptionalInt.empty());
        }

        BoundedProcessExecutor.ExecutionFinished finished =
                (BoundedProcessExecutor.ExecutionFinished) execution;
        if (finished.exitCode() != 0) {
            return new Failure(FailureReason.NONZERO_EXIT, OptionalInt.of(finished.exitCode()));
        }
        return new Output(finished.stdout());
    }

    private List<String> command(Path exactPath) {
        var command = new ArrayList<String>(settings.commandPrefix().size() + 3);
        command.addAll(settings.commandPrefix());
        command.addAll(INFO_ARGUMENTS);
        command.add(exactPath.toString());
        return List.copyOf(command);
    }

    static MacOsDiskutilRunner forTesting(
            List<String> commandPrefix,
            Duration timeout,
            Duration terminationGrace,
            Duration cleanupTimeout,
            int stdoutLimitBytes,
            int stderrLimitBytes) {
        return new MacOsDiskutilRunner(
                new BoundedProcessExecutor("media-compare-diskutil-reader-"),
                new Settings(commandPrefix, timeout, terminationGrace, cleanupTimeout,
                        stdoutLimitBytes, stderrLimitBytes));
    }

    static List<String> infoArguments() {
        return INFO_ARGUMENTS;
    }

    public sealed interface Result permits Output, Failure {
    }

    public record Output(byte[] plist) implements Result {
        public Output {
            plist = plist.clone();
        }

        @Override
        public byte[] plist() {
            return plist.clone();
        }
    }

    public record Failure(FailureReason reason, OptionalInt exitCode) implements Result {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(exitCode, "exitCode");
        }
    }

    public enum FailureReason {
        EXECUTABLE_UNAVAILABLE,
        NONZERO_EXIT,
        TIMEOUT,
        STDOUT_LIMIT_EXCEEDED,
        STDERR_LIMIT_EXCEEDED,
        PROCESS_ERROR
    }

    private record Settings(
            List<String> commandPrefix,
            Duration timeout,
            Duration terminationGrace,
            Duration cleanupTimeout,
            int stdoutLimitBytes,
            int stderrLimitBytes) {

        private Settings {
            commandPrefix = List.copyOf(commandPrefix);
            if (commandPrefix.isEmpty() || commandPrefix.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("diskutil command must not be empty or blank");
            }
        }

        private static Settings defaults() {
            return new Settings(
                    List.of("diskutil"),
                    DEFAULT_TIMEOUT,
                    DEFAULT_TERMINATION_GRACE,
                    DEFAULT_CLEANUP_TIMEOUT,
                    DEFAULT_STDOUT_LIMIT_BYTES,
                    DEFAULT_STDERR_LIMIT_BYTES);
        }
    }
}
