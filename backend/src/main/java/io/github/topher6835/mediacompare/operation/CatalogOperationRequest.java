package io.github.topher6835.mediacompare.operation;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.matching.CleanupPreflightRequest;

/** Immutable input; no browser path, cached authority or previous preflight result supplies permission. */
public record CatalogOperationRequest(Kind kind, Long sourceId, Long fileEntryId,
        String digestHex, CleanupPreflightRequest preflight) {
    public enum Kind { CLEANUP_PREFLIGHT, PREPARE_SOURCE, REVEAL_FILE }

    public CatalogOperationRequest {
        if (kind == null) throw new IllegalArgumentException("Operation kind is required");
        boolean valid = switch (kind) {
            case CLEANUP_PREFLIGHT -> sourceId == null && fileEntryId == null && preflight != null
                    && Sha256AnalysisDefinition.isValidDigest(digestHex);
            case PREPARE_SOURCE -> sourceId != null && sourceId > 0 && fileEntryId == null
                    && digestHex == null && preflight == null;
            case REVEAL_FILE -> fileEntryId != null && fileEntryId > 0 && sourceId == null
                    && digestHex == null && preflight == null;
        };
        if (!valid) throw new IllegalArgumentException("Invalid operation input");
    }
}
