package io.github.topher6835.mediacompare.location;

import java.util.Objects;

public record ContinuityVerificationResult(ContinuityOutcome outcome, ContinuityReason reason) {

    public ContinuityVerificationResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        if (!isAllowed(outcome, reason)) {
            throw new IllegalArgumentException("Continuity outcome and reason are incompatible");
        }
    }

    private static boolean isAllowed(ContinuityOutcome outcome, ContinuityReason reason) {
        return switch (outcome) {
            case ACCEPTED -> reason == ContinuityReason.EVIDENCE_MATCHED;
            case UNAVAILABLE -> reason == ContinuityReason.PROBE_UNAVAILABLE;
            case UNCERTAIN -> reason == ContinuityReason.PROBE_UNCERTAIN
                    || reason == ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT;
            case UNSUPPORTED -> reason == ContinuityReason.PROFILE_UNSUPPORTED;
            case ERROR -> reason == ContinuityReason.PROBE_ERROR;
            case MISMATCH -> switch (reason) {
                case ANCHOR_LOCATION_MISMATCH,
                        CONTEXT_VOLUME_UUID_MISMATCH,
                        CONTEXT_INODE_MISMATCH,
                        ANCHOR_DIRECTORY_MISMATCH,
                        ANCHOR_SYMBOLIC_LINK_MISMATCH,
                        SOURCE_CONTEXT_ID_MISMATCH,
                        CONTEXT_REVISION_MISMATCH,
                        SOURCE_LOCATION_REVISION_MISMATCH,
                        SOURCE_ROOT_LOCATION_MISMATCH,
                        SOURCE_ROOT_VOLUME_UUID_MISMATCH,
                        SOURCE_ROOT_INODE_MISMATCH,
                        SOURCE_ROOT_BIRTH_TIME_MISMATCH,
                        SOURCE_ROOT_DIRECTORY_MISMATCH,
                        SOURCE_ROOT_SYMBOLIC_LINK_MISMATCH -> true;
                default -> false;
            };
        };
    }

    public static ContinuityVerificationResult accepted() {
        return new ContinuityVerificationResult(
                ContinuityOutcome.ACCEPTED, ContinuityReason.EVIDENCE_MATCHED);
    }

    public static ContinuityVerificationResult mismatch(ContinuityReason reason) {
        return new ContinuityVerificationResult(ContinuityOutcome.MISMATCH, reason);
    }

    public static ContinuityVerificationResult uncertain(ContinuityReason reason) {
        return new ContinuityVerificationResult(ContinuityOutcome.UNCERTAIN, reason);
    }

    public static ContinuityVerificationResult unsupported(ContinuityReason reason) {
        return new ContinuityVerificationResult(ContinuityOutcome.UNSUPPORTED, reason);
    }
}
