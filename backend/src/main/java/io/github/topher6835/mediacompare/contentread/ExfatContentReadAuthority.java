package io.github.topher6835.mediacompare.contentread;

import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityScope;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry.WindowId;
import java.util.Objects;
import java.util.UUID;

/** Exact process-local owner. A receipt's old runtime/window is never an authorization. */
public record ExfatContentReadAuthority(ExfatAuthorityScope scope, WindowId window,
        String bundleId, Long metadataJobId, String thumbnailBatchId) {
    public ExfatContentReadAuthority {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(window);
        requireUuid(bundleId);
        if (window.sourceId() != scope.source().id()
                || (metadataJobId == null) == (thumbnailBatchId == null)
                || metadataJobId != null && metadataJobId <= 0) {
            throw new IllegalArgumentException("Invalid content-read owner");
        }
        if (thumbnailBatchId != null) requireUuid(thumbnailBatchId);
    }

    private static void requireUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (uuid.version() != 4 || uuid.variant() != 2 || !uuid.toString().equals(value)) {
            throw new IllegalArgumentException("Expected canonical runtime UUID");
        }
    }
}
