package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;

/** Typed observations captured before the first-binding transaction. */
public record SourceBindingCapture(
        long sourceId,
        String configuredRootPathSnapshot,
        ContinuityProbeResult<MacOsApfsLocationContextEvidence> contextProbeResult,
        ContinuityProbeResult<MacOsApfsSourceRootEvidence> sourceRootProbeResult) {
}
