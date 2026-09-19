package io.github.topher6835.mediacompare.catalog;

import java.util.Objects;
import java.util.UUID;

public record LocationContext(
        String id,
        String anchorLocationPath,
        String anchorLocationKey,
        LifecycleStatus lifecycleStatus,
        ContinuityStatus continuityStatus,
        long revision,
        String continuityEvidenceJson,
        long createdAtMs,
        long updatedAtMs) {

    public LocationContext {
        requireNonBlank(id, "ID");
        requireCanonicalUuid(id);
        requireNonBlank(anchorLocationPath, "Anchor location path");
        requireNonBlank(anchorLocationKey, "Anchor location key");
        Objects.requireNonNull(lifecycleStatus, "Lifecycle status is required");
        Objects.requireNonNull(continuityStatus, "Continuity status is required");
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        if (createdAtMs < 0 || updatedAtMs < createdAtMs) {
            throw new IllegalArgumentException("Timestamps are invalid");
        }
        if (continuityStatus == ContinuityStatus.ACCEPTED && continuityEvidenceJson == null) {
            throw new IllegalArgumentException("Accepted continuity requires evidence");
        }
    }

    private static void requireNonBlank(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
    }

    private static void requireCanonicalUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("ID must be canonical UUID text");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ID must be canonical UUID text", exception);
        }
    }

    public enum LifecycleStatus {
        ACTIVE,
        RETIRED
    }

    public enum ContinuityStatus {
        ACCEPTED,
        REVIEW_REQUIRED
    }
}
