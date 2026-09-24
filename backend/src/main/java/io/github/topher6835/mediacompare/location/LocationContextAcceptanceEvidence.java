package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Provenance for the revision at which a LocationContext became accepted. */
public record LocationContextAcceptanceEvidence(
        int version,
        String contextId,
        long contextRevision,
        MacOsApfsLocationContextEvidence macOsApfsEvidence) {
    public static final int VERSION = 1;

    public LocationContextAcceptanceEvidence {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported LocationContext acceptance evidence version");
        }
        contextId = EvidenceValues.canonicalUuid(contextId, "LocationContext ID");
        contextRevision = EvidenceValues.nonnegative(contextRevision, "LocationContext revision");
        macOsApfsEvidence = Objects.requireNonNull(macOsApfsEvidence, "APFS context evidence is required");
    }
}
