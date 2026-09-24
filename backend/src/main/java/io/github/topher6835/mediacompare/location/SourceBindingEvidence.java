package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Source-owned provenance for an accepted first-time APFS root binding. */
public record SourceBindingEvidence(int version, long sourceId,
        MacOsApfsSourceRootEvidence macOsApfsSourceRootEvidence) {
    public static final int VERSION = 1;

    public SourceBindingEvidence {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported Source binding evidence version");
        }
        if (sourceId <= 0) {
            throw new IllegalArgumentException("Source ID must be positive");
        }
        macOsApfsSourceRootEvidence = Objects.requireNonNull(
                macOsApfsSourceRootEvidence, "APFS Source-root evidence is required");
    }
}
