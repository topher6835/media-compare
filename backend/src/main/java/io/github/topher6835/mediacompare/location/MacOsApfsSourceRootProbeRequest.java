package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Caller-owned durable identity and revision inputs for Source-root evidence capture. */
public record MacOsApfsSourceRootProbeRequest(
        String locationContextId,
        long locationContextRevision,
        long sourceLocationRevision,
        MacOsApfsLocationContextEvidence locationContextEvidence,
        LocationPath requestedRoot) {

    public MacOsApfsSourceRootProbeRequest {
        locationContextId = EvidenceValues.canonicalUuid(locationContextId, "LocationContext ID");
        locationContextRevision = EvidenceValues.nonnegative(
                locationContextRevision, "LocationContext revision");
        sourceLocationRevision = EvidenceValues.nonnegative(
                sourceLocationRevision, "Source location revision");
        locationContextEvidence = Objects.requireNonNull(
                locationContextEvidence, "LocationContext evidence is required");
        requestedRoot = Objects.requireNonNull(requestedRoot, "Requested Source root is required");
    }
}
