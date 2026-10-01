package io.github.topher6835.mediacompare.filesystem;

import java.util.UUID;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Persisted acceptance baseline for one local NTFS drive anchor. */
public record WindowsNtfsContextEvidence(String contextId, long contextRevision,
        LocationPath anchor, WindowsNtfsIdentity identity) {
    public WindowsNtfsContextEvidence {
        if (contextId == null || !UUID.fromString(contextId).toString().equals(contextId)
                || contextRevision < 0 || anchor == null
                || anchor.dialect() != LocationDialect.WINDOWS_DRIVE
                || !anchor.components().isEmpty() || identity == null) {
            throw new IllegalArgumentException("Invalid NTFS context evidence");
        }
    }
}
