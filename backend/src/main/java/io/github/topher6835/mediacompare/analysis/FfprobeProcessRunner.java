package io.github.topher6835.mediacompare.analysis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.topher6835.mediacompare.process.BoundedProcessExecutor;
import io.github.topher6835.mediacompare.process.BoundedProcessInterruptedException;

@Component
public final class FfprobeProcessRunner {

    static final Duration DEFAULT_QUALIFICATION_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(30);
    static final int DEFAULT_STDOUT_LIMIT_BYTES = 4 * 1_024 * 1_024;
    static final int DEFAULT_STDERR_LIMIT_BYTES = 256 * 1_024;
    private static final Duration DEFAULT_TERMINATION_GRACE = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CLEANUP_TIMEOUT = Duration.ofSeconds(2);

    private static final List<String> VERSION_ARGUMENTS = List.of("-version");
    private static final List<String> PROTOCOL_ARGUMENTS = List.of("-v", "error", "-protocols");
    private static final List<String> MOV_HELP_ARGUMENTS = List.of(
            "-v", "error", "-hide_banner", "-h", "demuxer=mov");
    private static final List<String> PROBE_ARGUMENTS = List.of(
            "-v", "error",
            "-protocol_whitelist", "fd",
            "-enable_drefs", "0",
            "-use_absolute_path", "0",
            "-show_entries",
            "format=format_name,duration:format_tags=major_brand,compatible_brands:"
                    + "stream=index,codec_type,codec_name,width,height:"
                    + "stream_disposition=default,attached_pic,timed_thumbnails,still_image",
            "-of", "json",
            "-i", "fd:");

    private final BoundedProcessExecutor executor;
    private final Settings settings;
    private Qualification qualification;

    @Autowired
    public FfprobeProcessRunner(
            @Value("${media-compare.ffprobe.executable:#{null}}") String executable) {
        this(new BoundedProcessExecutor("media-compare-ffprobe-reader-"),
                Settings.defaults(configuredExecutable(executable)));
    }

    private FfprobeProcessRunner(BoundedProcessExecutor executor, Settings settings) {
        this.executor = executor;
        this.settings = settings;
    }

    public FfprobeProcessResult probe(Path validatedFile) {
        Objects.requireNonNull(validatedFile, "validatedFile");
        Qualification currentQualification = qualify();
        if (currentQualification.failure() != null) {
            return currentQualification.failure();
        }

        try {
            BoundedProcessExecutor.Execution execution = executor.execute(
                    command(PROBE_ARGUMENTS),
                    validatedFile,
                    settings.probeTimeout(),
                    settings.terminationGrace(),
                    settings.cleanupTimeout(),
                    settings.stdoutLimitBytes(),
                    settings.stderrLimitBytes());
            return probeResult(execution);
        } catch (BoundedProcessInterruptedException exception) {
            throw new FfprobeRunnerInterruptedException();
        }
    }

    private synchronized Qualification qualify() {
        if (qualification != null) {
            return qualification;
        }

        BoundedProcessExecutor.Execution version = executeQualification(VERSION_ARGUMENTS);
        FfprobeProcessResult.InfrastructureFailure versionFailure = qualificationExecutionFailure(version);
        if (versionFailure != null) {
            qualification = new Qualification(versionFailure);
            return qualification;
        }
        BoundedProcessExecutor.ExecutionFinished versionOutput =
                (BoundedProcessExecutor.ExecutionFinished) version;
        String versionText;
        try {
            versionText = decodeStrictUtf8(versionOutput.stdout());
        } catch (CharacterCodingException exception) {
            qualification = failedQualification(
                    FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                    "ffprobe version output was not valid UTF-8");
            return qualification;
        }
        if (!versionText.startsWith("ffprobe version ")) {
            qualification = failedQualification(
                    FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                    "Configured executable did not identify itself as ffprobe");
            return qualification;
        }

        BoundedProcessExecutor.Execution protocols = executeQualification(PROTOCOL_ARGUMENTS);
        FfprobeProcessResult.InfrastructureFailure protocolsFailure = qualificationExecutionFailure(protocols);
        if (protocolsFailure != null) {
            qualification = new Qualification(protocolsFailure);
            return qualification;
        }
        BoundedProcessExecutor.ExecutionFinished protocolOutput =
                (BoundedProcessExecutor.ExecutionFinished) protocols;
        String protocolText;
        try {
            protocolText = decodeStrictUtf8(protocolOutput.stdout());
        } catch (CharacterCodingException exception) {
            qualification = failedQualification(
                    FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                    "ffprobe protocol output was not valid UTF-8");
            return qualification;
        }
        if (!supportsFdInput(protocolText)) {
            qualification = failedQualification(
                    FfprobeProcessResult.InfrastructureFailureReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                    "Configured ffprobe does not advertise the fd input protocol");
            return qualification;
        }

        BoundedProcessExecutor.Execution movHelp = executeQualification(MOV_HELP_ARGUMENTS);
        FfprobeProcessResult.InfrastructureFailure movHelpFailure = qualificationExecutionFailure(movHelp);
        if (movHelpFailure != null) {
            qualification = new Qualification(movHelpFailure);
            return qualification;
        }
        BoundedProcessExecutor.ExecutionFinished movHelpOutput =
                (BoundedProcessExecutor.ExecutionFinished) movHelp;
        String movHelpText;
        try {
            movHelpText = decodeStrictUtf8(movHelpOutput.stdout());
        } catch (CharacterCodingException exception) {
            qualification = failedQualification(
                    FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                    "ffprobe MOV capability output was not valid UTF-8");
            return qualification;
        }
        if (!supportsRequiredMovOptions(movHelpText)) {
            qualification = failedQualification(
                    FfprobeProcessResult.InfrastructureFailureReason.REQUIRED_CAPABILITY_UNAVAILABLE,
                    "Configured ffprobe lacks required MOV external-reference controls");
            return qualification;
        }

        qualification = new Qualification(null);
        return qualification;
    }

    private BoundedProcessExecutor.Execution executeQualification(List<String> arguments) {
        try {
            return executor.execute(
                    command(arguments),
                    null,
                    settings.qualificationTimeout(),
                    settings.terminationGrace(),
                    settings.cleanupTimeout(),
                    settings.stdoutLimitBytes(),
                    settings.stderrLimitBytes());
        } catch (BoundedProcessInterruptedException exception) {
            throw new FfprobeRunnerInterruptedException();
        }
    }

    private FfprobeProcessResult.InfrastructureFailure qualificationExecutionFailure(
            BoundedProcessExecutor.Execution execution) {
        if (execution instanceof BoundedProcessExecutor.ExecutionFailed failed) {
            FfprobeProcessResult.InfrastructureFailureReason reason = switch (failed.reason()) {
                case START_FAILED -> FfprobeProcessResult.InfrastructureFailureReason.EXECUTABLE_UNAVAILABLE;
                case SETUP_FAILED -> FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED;
                case TIMEOUT -> FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_TIMEOUT;
                case CLEANUP_FAILED -> FfprobeProcessResult.InfrastructureFailureReason.CLEANUP_FAILED;
                default -> FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED;
            };
            return infrastructureFailure(reason, failed.diagnostic());
        }

        BoundedProcessExecutor.ExecutionFinished finished =
                (BoundedProcessExecutor.ExecutionFinished) execution;
        if (finished.exitCode() != 0) {
            return infrastructureFailure(
                    FfprobeProcessResult.InfrastructureFailureReason.QUALIFICATION_FAILED,
                    finished.stderr());
        }
        return null;
    }

    private FfprobeProcessResult probeResult(BoundedProcessExecutor.Execution execution) {
        if (execution instanceof BoundedProcessExecutor.ExecutionFailed failed) {
            return switch (failed.reason()) {
                case TIMEOUT -> probeFailure(
                        FfprobeProcessResult.ProbeFailureReason.PROCESS_TIMEOUT,
                        failed.diagnostic());
                case STDOUT_LIMIT_EXCEEDED -> probeFailure(
                        FfprobeProcessResult.ProbeFailureReason.STDOUT_LIMIT_EXCEEDED,
                        failed.diagnostic());
                case STDERR_LIMIT_EXCEEDED -> probeFailure(
                        FfprobeProcessResult.ProbeFailureReason.STDERR_LIMIT_EXCEEDED,
                        failed.diagnostic());
                case START_FAILED, SETUP_FAILED -> infrastructureFailure(
                        FfprobeProcessResult.InfrastructureFailureReason.PROCESS_START_FAILED,
                        failed.diagnostic());
                case STREAM_READ_FAILED -> infrastructureFailure(
                        FfprobeProcessResult.InfrastructureFailureReason.STREAM_READ_FAILED,
                        failed.diagnostic());
                case CLEANUP_FAILED -> infrastructureFailure(
                        FfprobeProcessResult.InfrastructureFailureReason.CLEANUP_FAILED,
                        failed.diagnostic());
            };
        }

        BoundedProcessExecutor.ExecutionFinished finished =
                (BoundedProcessExecutor.ExecutionFinished) execution;
        String diagnostic = decodeDiagnostic(finished.stderr());
        if (finished.exitCode() != 0) {
            return new FfprobeProcessResult.ProbeFailure(
                    FfprobeProcessResult.ProbeFailureReason.NONZERO_EXIT,
                    OptionalInt.of(finished.exitCode()),
                    diagnostic);
        }
        try {
            return new FfprobeProcessResult.ProbeOutput(
                    decodeStrictUtf8(finished.stdout()), diagnostic);
        } catch (CharacterCodingException exception) {
            return new FfprobeProcessResult.ProbeFailure(
                    FfprobeProcessResult.ProbeFailureReason.INVALID_UTF8_OUTPUT,
                    OptionalInt.of(finished.exitCode()),
                    diagnostic);
        }
    }

    private List<String> command(List<String> arguments) {
        var command = new ArrayList<String>(settings.commandPrefix().size() + arguments.size());
        command.addAll(settings.commandPrefix());
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static String configuredExecutable(String executable) {
        if (executable == null) {
            return "ffprobe";
        }
        if (executable.isBlank()) {
            throw new IllegalArgumentException("Configured ffprobe executable must not be blank");
        }
        if (!executable.equals(executable.trim())) {
            throw new IllegalArgumentException(
                    "Configured ffprobe executable must not contain surrounding whitespace");
        }
        if (!Path.of(executable).isAbsolute()) {
            throw new IllegalArgumentException(
                    "Configured ffprobe executable must be an absolute path");
        }
        return executable;
    }

    private static boolean supportsFdInput(String protocolOutput) {
        boolean inputSection = false;
        for (String line : protocolOutput.lines().toList()) {
            String value = line.trim();
            if ("Input:".equals(value)) {
                inputSection = true;
            } else if ("Output:".equals(value)) {
                inputSection = false;
            } else if (inputSection && "fd".equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsRequiredMovOptions(String helpOutput) {
        boolean enablesDataReferences = false;
        boolean controlsAbsolutePaths = false;
        for (String line : helpOutput.lines().toList()) {
            String value = line.trim();
            enablesDataReferences |= value.startsWith("-enable_drefs ");
            controlsAbsolutePaths |= value.startsWith("-use_absolute_path ");
        }
        return enablesDataReferences && controlsAbsolutePaths;
    }

    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String decodeDiagnostic(byte[] bytes) {
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static FfprobeProcessResult.InfrastructureFailure infrastructureFailure(
            FfprobeProcessResult.InfrastructureFailureReason reason, byte[] diagnostic) {
        return new FfprobeProcessResult.InfrastructureFailure(reason, decodeDiagnostic(diagnostic));
    }

    private static FfprobeProcessResult.ProbeFailure probeFailure(
            FfprobeProcessResult.ProbeFailureReason reason, byte[] diagnostic) {
        return new FfprobeProcessResult.ProbeFailure(
                reason, OptionalInt.empty(), decodeDiagnostic(diagnostic));
    }

    private static Qualification failedQualification(
            FfprobeProcessResult.InfrastructureFailureReason reason, String diagnostic) {
        return new Qualification(new FfprobeProcessResult.InfrastructureFailure(reason, diagnostic));
    }

    static FfprobeProcessRunner forTesting(
            List<String> commandPrefix,
            Duration qualificationTimeout,
            Duration probeTimeout,
            Duration terminationGrace,
            Duration cleanupTimeout,
            int stdoutLimitBytes,
            int stderrLimitBytes) {
        return new FfprobeProcessRunner(
                new BoundedProcessExecutor("media-compare-ffprobe-reader-"),
                new Settings(
                        commandPrefix,
                        qualificationTimeout,
                        probeTimeout,
                        terminationGrace,
                        cleanupTimeout,
                        stdoutLimitBytes,
                        stderrLimitBytes));
    }

    static List<String> probeArguments() {
        return PROBE_ARGUMENTS;
    }

    private record Qualification(FfprobeProcessResult.InfrastructureFailure failure) {
    }

    private record Settings(
            List<String> commandPrefix,
            Duration qualificationTimeout,
            Duration probeTimeout,
            Duration terminationGrace,
            Duration cleanupTimeout,
            int stdoutLimitBytes,
            int stderrLimitBytes) {

        private Settings {
            commandPrefix = List.copyOf(commandPrefix);
            if (commandPrefix.isEmpty() || commandPrefix.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("ffprobe command must not be empty or blank");
            }
            requirePositive(qualificationTimeout, "qualificationTimeout");
            requirePositive(probeTimeout, "probeTimeout");
            requirePositive(terminationGrace, "terminationGrace");
            requirePositive(cleanupTimeout, "cleanupTimeout");
            if (stdoutLimitBytes <= 0 || stderrLimitBytes <= 0) {
                throw new IllegalArgumentException("ffprobe output limits must be positive");
            }
        }

        private static Settings defaults(String executable) {
            return new Settings(
                    List.of(executable),
                    DEFAULT_QUALIFICATION_TIMEOUT,
                    DEFAULT_PROBE_TIMEOUT,
                    DEFAULT_TERMINATION_GRACE,
                    DEFAULT_CLEANUP_TIMEOUT,
                    DEFAULT_STDOUT_LIMIT_BYTES,
                    DEFAULT_STDERR_LIMIT_BYTES);
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
