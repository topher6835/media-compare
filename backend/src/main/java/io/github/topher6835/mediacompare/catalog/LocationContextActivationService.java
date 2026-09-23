package io.github.topher6835.mediacompare.catalog;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationPath;

@Service
public class LocationContextActivationService {
    private final LocationContextRepository contexts;

    public LocationContextActivationService(LocationContextRepository contexts) {
        this.contexts = contexts;
    }

    @Transactional
    public LocationContext createActive(LocationContext requested) {
        Objects.requireNonNull(requested, "Requested LocationContext is required");
        if (requested.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE) {
            throw new IllegalArgumentException("Requested LocationContext must be ACTIVE");
        }
        LocationPath requestedAnchor = LocationAnchorPolicy.validateAnchor(
                requested.anchorLocationPath(), requested.anchorLocationKey());

        contexts.reserveWrite();
        for (LocationContext existing : contexts.findActive()) {
            final LocationPath existingAnchor;
            try {
                existingAnchor = LocationAnchorPolicy.validateAnchor(
                        existing.anchorLocationPath(), existing.anchorLocationKey());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Invalid anchor for active LocationContext " + existing.id(), exception);
            }
            if (LocationAnchorPolicy.structurallyOverlaps(existingAnchor, requestedAnchor)) {
                throw new LocationContextStructuralOverlapException(existing.id());
            }
        }
        return contexts.insert(requested);
    }
}
