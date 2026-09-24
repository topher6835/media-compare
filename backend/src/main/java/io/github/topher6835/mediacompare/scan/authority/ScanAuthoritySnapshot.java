package io.github.topher6835.mediacompare.scan.authority;

import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;

/** Immutable current-row authority plus a fresh accepted context/root observation. */
public final class ScanAuthoritySnapshot {
    private final long sourceId;
    private final long sourceRevision;
    private final String contextId;
    private final long contextRevision;
    private final LocationPath sourceRoot;
    private final LocationPath contextAnchor;
    private final MacOsApfsLocationContextEvidence contextBaseline;
    private final MacOsApfsLocationContextEvidence contextObservation;
    private final MacOsApfsSourceRootEvidence rootBaseline;
    private final MacOsApfsSourceRootEvidence rootObservation;

    ScanAuthoritySnapshot(long sourceId, long sourceRevision, String contextId, long contextRevision,
            LocationPath sourceRoot, LocationPath contextAnchor,
            MacOsApfsLocationContextEvidence contextBaseline,
            MacOsApfsLocationContextEvidence contextObservation,
            MacOsApfsSourceRootEvidence rootBaseline,
            MacOsApfsSourceRootEvidence rootObservation) {
        this.sourceId = sourceId;
        this.sourceRevision = sourceRevision;
        this.contextId = contextId;
        this.contextRevision = contextRevision;
        this.sourceRoot = sourceRoot;
        this.contextAnchor = contextAnchor;
        this.contextBaseline = contextBaseline;
        this.contextObservation = contextObservation;
        this.rootBaseline = rootBaseline;
        this.rootObservation = rootObservation;
    }

    public long sourceId() {
        return sourceId;
    }

    public long sourceRevision() {
        return sourceRevision;
    }

    public String contextId() {
        return contextId;
    }

    public long contextRevision() {
        return contextRevision;
    }

    public LocationPath sourceRoot() {
        return sourceRoot;
    }

    public LocationPath contextAnchor() {
        return contextAnchor;
    }

    public MacOsApfsLocationContextEvidence contextBaseline() {
        return contextBaseline;
    }

    public MacOsApfsLocationContextEvidence contextObservation() {
        return contextObservation;
    }

    public MacOsApfsSourceRootEvidence rootBaseline() {
        return rootBaseline;
    }

    public MacOsApfsSourceRootEvidence rootObservation() {
        return rootObservation;
    }
}
