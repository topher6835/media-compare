package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;
import java.util.OptionalInt;

public sealed interface FfprobeProcessResult
        permits FfprobeProcessResult.ProbeOutput,
                FfprobeProcessResult.ProbeFailure,
                FfprobeProcessResult.InfrastructureFailure {

    record ProbeOutput(String json, String diagnostic) implements FfprobeProcessResult {
        public ProbeOutput {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    record ProbeFailure(
            ProbeFailureReason reason,
            OptionalInt exitCode,
            String diagnostic) implements FfprobeProcessResult {
        public ProbeFailure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(exitCode, "exitCode");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    record InfrastructureFailure(
            InfrastructureFailureReason reason,
            String diagnostic) implements FfprobeProcessResult {
        public InfrastructureFailure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }
    }

    enum ProbeFailureReason {
        NONZERO_EXIT,
        INVALID_UTF8_OUTPUT,
        PROCESS_TIMEOUT,
        STDOUT_LIMIT_EXCEEDED,
        STDERR_LIMIT_EXCEEDED
    }

    enum InfrastructureFailureReason {
        EXECUTABLE_UNAVAILABLE,
        QUALIFICATION_FAILED,
        QUALIFICATION_TIMEOUT,
        REQUIRED_CAPABILITY_UNAVAILABLE,
        PROCESS_START_FAILED,
        STREAM_READ_FAILED,
        CLEANUP_FAILED
    }
}
