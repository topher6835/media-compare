package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;

public sealed interface FfprobeInterpretationResult
        permits FfprobeInterpretationResult.Completed, FfprobeInterpretationResult.Failed {

    record Completed(MediaMetadataResult result) implements FfprobeInterpretationResult {
        public Completed {
            Objects.requireNonNull(result, "result");
        }
    }

    record Failed(FailureReason reason) implements FfprobeInterpretationResult {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FailureReason {
        MALFORMED_PROBE_OUTPUT,
        INVALID_MEDIA_METADATA
    }
}
