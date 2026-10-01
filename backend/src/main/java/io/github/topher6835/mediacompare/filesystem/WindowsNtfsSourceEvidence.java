package io.github.topher6835.mediacompare.filesystem;

import java.util.UUID;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Persisted binding baseline for one Source root on its accepted NTFS drive. */
public record WindowsNtfsSourceEvidence(long sourceId, long sourceRevision,
        String contextId, long contextRevision, LocationPath root, WindowsNtfsIdentity identity) {
    public WindowsNtfsSourceEvidence {
        if (sourceId <= 0 || sourceRevision < 0 || contextRevision < 0
                || contextId == null || !UUID.fromString(contextId).toString().equals(contextId)
                || root == null || root.dialect() != LocationDialect.WINDOWS_DRIVE
                || identity == null) {
            throw new IllegalArgumentException("Invalid NTFS Source evidence");
        }
    }
}
