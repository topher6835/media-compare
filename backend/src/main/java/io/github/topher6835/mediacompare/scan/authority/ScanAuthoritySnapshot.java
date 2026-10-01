package io.github.topher6835.mediacompare.scan.authority;

import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsContextEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsSourceEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityVerifier;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootComparisonContext;

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
    private final WindowsNtfsContextEvidence windowsContextBaseline;
    private final WindowsNtfsIdentity windowsContextObservation;
    private final WindowsNtfsSourceEvidence windowsRootBaseline;
    private final WindowsNtfsIdentity windowsRootObservation;

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
        this.windowsContextBaseline = null;
        this.windowsContextObservation = null;
        this.windowsRootBaseline = null;
        this.windowsRootObservation = null;
    }

    ScanAuthoritySnapshot(long sourceId, long sourceRevision, String contextId, long contextRevision,
            WindowsNtfsContextEvidence contextBaseline, WindowsNtfsIdentity contextObservation,
            WindowsNtfsSourceEvidence rootBaseline, WindowsNtfsIdentity rootObservation) {
        this.sourceId = sourceId;
        this.sourceRevision = sourceRevision;
        this.contextId = contextId;
        this.contextRevision = contextRevision;
        this.sourceRoot = rootBaseline.root();
        this.contextAnchor = contextBaseline.anchor();
        this.contextBaseline = null;
        this.contextObservation = null;
        this.rootBaseline = null;
        this.rootObservation = null;
        this.windowsContextBaseline = contextBaseline;
        this.windowsContextObservation = contextObservation;
        this.windowsRootBaseline = rootBaseline;
        this.windowsRootObservation = rootObservation;
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

    public boolean windowsNtfs() { return windowsContextBaseline != null; }

    public String volumeId() {
        return windowsNtfs() ? windowsContextBaseline.identity().volumeSerial()
                : contextBaseline.volumeUuid();
    }

    public String fileSystemType() { return windowsNtfs() ? "ntfs" : "apfs"; }

    public boolean sameRootAs(ScanAuthoritySnapshot other) {
        if (other == null || windowsNtfs() != other.windowsNtfs()
                || sourceId != other.sourceId || sourceRevision != other.sourceRevision
                || contextRevision != other.contextRevision || !contextId.equals(other.contextId)
                || !sourceRoot.equals(other.sourceRoot)) return false;
        if (windowsNtfs()) {
            return windowsRootBaseline.equals(other.windowsRootBaseline)
                    && windowsRootObservation.equals(other.windowsRootObservation);
        }
        return rootBaseline.equals(other.rootBaseline)
                && MacOsApfsContinuityVerifier.verifySourceRoot(new MacOsApfsSourceRootComparisonContext(
                        contextId, contextRevision, sourceRevision, contextBaseline),
                        rootObservation, other.rootObservation).outcome() == ContinuityOutcome.ACCEPTED;
    }

    public boolean sameContextAs(ScanAuthoritySnapshot other) {
        if (other == null || windowsNtfs() != other.windowsNtfs()
                || !contextId.equals(other.contextId) || contextRevision != other.contextRevision) return false;
        if (windowsNtfs()) {
            return windowsContextBaseline.equals(other.windowsContextBaseline)
                    && windowsContextObservation.equals(other.windowsContextObservation);
        }
        return contextBaseline.equals(other.contextBaseline)
                && MacOsApfsContinuityVerifier.verifyLocationContext(
                        contextObservation, other.contextObservation).outcome() == ContinuityOutcome.ACCEPTED;
    }
}
