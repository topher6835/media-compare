package io.github.topher6835.mediacompare.catalog;

import org.springframework.stereotype.Component;

import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityProbe;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLogicalAnchorResolver;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootProbeRequest;

@Component
public class MacOsSourcePreparationProbe implements SourcePreparationProbe {
    private final MacOsApfsLogicalAnchorResolver anchors = new MacOsApfsLogicalAnchorResolver();
    private final MacOsApfsContinuityProbe continuity = new MacOsApfsContinuityProbe();

    @Override
    public ContinuityProbeResult<LocationPath> resolveAnchor(LocationPath root) {
        return anchors.resolve(root);
    }

    @Override
    public ContinuityProbeResult<MacOsApfsLocationContextEvidence> captureContext(LocationPath anchor) {
        return continuity.captureLocationContext(anchor);
    }

    @Override
    public ContinuityProbeResult<MacOsApfsSourceRootEvidence> captureRoot(
            MacOsApfsSourceRootProbeRequest request) {
        return continuity.captureSourceRoot(request);
    }
}
