package io.github.topher6835.mediacompare.web;

import java.util.UUID;

/** Runtime is implicitly the currently running backend; UUIDs are never reconstructed from history. */
public record SourceAuthorityWindowRequest(long sourceId, String windowId) {
    public SourceAuthorityWindowRequest {
        if (sourceId <= 0 || windowId == null || !UUID.fromString(windowId).toString().equals(windowId)
                || UUID.fromString(windowId).version() != 4 || UUID.fromString(windowId).variant() != 2) throw new IllegalArgumentException("Invalid authority window");
    }
}
