package io.github.topher6835.mediacompare.catalog;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Validates an ACTIVE + ACCEPTED row as current continuity authority. */
public final class LocationContextAcceptanceAuthority {
    private static final LocationContextAcceptanceEvidenceCodec CODEC =
            new LocationContextAcceptanceEvidenceCodec();

    private LocationContextAcceptanceAuthority() {
    }

    public static LocationContextAcceptanceEvidence requireCurrentAccepted(LocationContext context) {
        Objects.requireNonNull(context, "LocationContext is required");
        if (context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED) {
            throw new IllegalArgumentException("LocationContext is not current ACTIVE + ACCEPTED authority");
        }
        final LocationPath anchor;
        final LocationContextAcceptanceEvidence acceptance;
        try {
            anchor = LocationAnchorPolicy.validateAnchor(
                    context.anchorLocationPath(), context.anchorLocationKey());
            acceptance = CODEC.decode(context.continuityEvidenceJson());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid current acceptance evidence for LocationContext "
                    + context.id(), exception);
        }
        if (!acceptance.contextId().equals(context.id())
                || acceptance.contextRevision() != context.revision()
                || !acceptance.macOsApfsEvidence().anchorLocationPath().equals(anchor)
                || !acceptance.macOsApfsEvidence().anchorLocationKey().value().equals(context.anchorLocationKey())
                || !acceptance.macOsApfsEvidence().directory()
                || acceptance.macOsApfsEvidence().symbolicLink()) {
            throw new IllegalStateException("Acceptance evidence does not match current LocationContext "
                    + context.id());
        }
        return acceptance;
    }
}
