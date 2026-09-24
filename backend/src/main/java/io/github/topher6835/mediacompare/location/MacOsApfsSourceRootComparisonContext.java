package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Catalog-independent expected relationship for Source-root validation and comparison. */
public record MacOsApfsSourceRootComparisonContext(
        String locationContextId,
        long locationContextRevision,
        long sourceLocationRevision,
        MacOsApfsLocationContextEvidence locationContextEvidence) {

    public MacOsApfsSourceRootComparisonContext {
        locationContextId = EvidenceValues.canonicalUuid(locationContextId, "LocationContext ID");
        locationContextRevision = EvidenceValues.nonnegative(
                locationContextRevision, "LocationContext revision");
        sourceLocationRevision = EvidenceValues.nonnegative(sourceLocationRevision, "Source location revision");
        Objects.requireNonNull(locationContextEvidence, "LocationContext evidence is required");
    }
}
