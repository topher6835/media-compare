package io.github.topher6835.mediacompare.location;

import java.util.Objects;
import java.util.Optional;

/** A bounded probe result: accepted results carry trusted evidence and failures never do. */
public record ContinuityProbeResult<E>(
        ContinuityOutcome outcome,
        ContinuityReason reason,
        Optional<E> evidence) {

    public ContinuityProbeResult {
        new ContinuityVerificationResult(outcome, reason);
        evidence = Objects.requireNonNull(evidence, "evidence");
        if ((outcome == ContinuityOutcome.ACCEPTED) != evidence.isPresent()) {
            throw new IllegalArgumentException("Only an accepted probe result may carry evidence");
        }
    }

    public static <E> ContinuityProbeResult<E> accepted(E evidence) {
        return new ContinuityProbeResult<>(
                ContinuityOutcome.ACCEPTED,
                ContinuityReason.EVIDENCE_MATCHED,
                Optional.of(Objects.requireNonNull(evidence, "evidence")));
    }

    public static <E> ContinuityProbeResult<E> unavailable() {
        return failed(ContinuityOutcome.UNAVAILABLE, ContinuityReason.PROBE_UNAVAILABLE);
    }

    public static <E> ContinuityProbeResult<E> uncertain(ContinuityReason reason) {
        return failed(ContinuityOutcome.UNCERTAIN, reason);
    }

    public static <E> ContinuityProbeResult<E> unsupported() {
        return failed(ContinuityOutcome.UNSUPPORTED, ContinuityReason.PROFILE_UNSUPPORTED);
    }

    public static <E> ContinuityProbeResult<E> error() {
        return failed(ContinuityOutcome.ERROR, ContinuityReason.PROBE_ERROR);
    }

    public static <E> ContinuityProbeResult<E> mismatch(ContinuityReason reason) {
        return failed(ContinuityOutcome.MISMATCH, reason);
    }

    private static <E> ContinuityProbeResult<E> failed(
            ContinuityOutcome outcome, ContinuityReason reason) {
        return new ContinuityProbeResult<>(outcome, reason, Optional.empty());
    }
}
