package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootProbeRequest;

/** Small host observation boundary for explicit Source preparation. */
public interface SourcePreparationProbe {
    ContinuityProbeResult<LocationPath> resolveAnchor(LocationPath root);

    ContinuityProbeResult<MacOsApfsLocationContextEvidence> captureContext(LocationPath anchor);

    ContinuityProbeResult<MacOsApfsSourceRootEvidence> captureRoot(MacOsApfsSourceRootProbeRequest request);
}
