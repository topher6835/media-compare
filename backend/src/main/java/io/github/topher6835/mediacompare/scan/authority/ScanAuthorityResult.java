package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;
import java.util.Optional;

/** A trusted value or a bounded reason for refusing authority. */
public record ScanAuthorityResult<T>(ScanAuthorityOutcome outcome, ScanAuthorityReason reason,
        Optional<T> value) {

    public ScanAuthorityResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        value = Objects.requireNonNull(value, "value");
        if ((outcome == ScanAuthorityOutcome.TRUSTED) != value.isPresent()
                || !compatible(outcome, reason)) {
            throw new IllegalArgumentException("Scan authority outcome, reason, and value disagree");
        }
    }

    public static <T> ScanAuthorityResult<T> trusted(T value) {
        return new ScanAuthorityResult<>(ScanAuthorityOutcome.TRUSTED, ScanAuthorityReason.ACCEPTED,
                Optional.of(Objects.requireNonNull(value, "value")));
    }

    public static <T> ScanAuthorityResult<T> denied(ScanAuthorityOutcome outcome, ScanAuthorityReason reason) {
        return new ScanAuthorityResult<>(outcome, reason, Optional.empty());
    }

    private static boolean compatible(ScanAuthorityOutcome outcome, ScanAuthorityReason reason) {
        return switch (outcome) {
            case TRUSTED -> reason == ScanAuthorityReason.ACCEPTED;
            case UNBOUND -> reason == ScanAuthorityReason.SOURCE_UNBOUND;
            case UNSUPPORTED -> reason == ScanAuthorityReason.PROFILE_UNSUPPORTED
                    || reason == ScanAuthorityReason.CHILD_STORAGE_UNSUPPORTED;
            case UNAVAILABLE -> reason == ScanAuthorityReason.AUTHORITY_UNAVAILABLE;
            case UNCERTAIN -> switch (reason) {
                case AUTHORITY_UNCERTAIN, FILE_OUTSIDE_SOURCE, FILE_OUTSIDE_CONTEXT,
                        FILE_NOT_REGULAR, SYMBOLIC_LINK_BOUNDARY,
                        CHILD_STORAGE_UNCERTAIN, VOLUME_IDENTITY_MISMATCH -> true;
                default -> false;
            };
            case STALE -> switch (reason) {
                case CONTEXT_NOT_CURRENT, AUTHORITY_CHANGED, SOURCE_ID_CHANGED,
                        SOURCE_REVISION_CHANGED, CONTEXT_REVISION_CHANGED,
                        CONTEXT_ID_CHANGED, SOURCE_ROOT_CHANGED, TRAVERSAL_SCOPE_CHANGED -> true;
                default -> false;
            };
            case INCOMPLETE -> reason == ScanAuthorityReason.TRAVERSAL_ERROR
                    || reason == ScanAuthorityReason.TRAVERSAL_CANCELLED
                    || reason == ScanAuthorityReason.INACCESSIBLE_SUBTREE;
        };
    }
}
