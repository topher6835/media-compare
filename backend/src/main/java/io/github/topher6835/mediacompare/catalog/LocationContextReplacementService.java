package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationPath;

@Service
public class LocationContextReplacementService {
    private final LocationContextRepository contexts;

    public LocationContextReplacementService(LocationContextRepository contexts) {
        this.contexts = contexts;
    }

    @Transactional
    public LocationContext replaceActive(String oldContextId, long expectedRevision, long replacedAtMs,
            LocationContext newContext) {
        requireCanonicalId(oldContextId);
        if (expectedRevision < 0 || replacedAtMs < 0) {
            throw new IllegalArgumentException("Expected revision and replacement timestamp must not be negative");
        }
        if (newContext == null) {
            throw new IllegalArgumentException("New LocationContext is required");
        }

        contexts.reserveWrite();
        final LocationContext oldContext;
        try {
            oldContext = contexts.findById(oldContextId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "LocationContext " + oldContextId + " does not exist"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid persisted LocationContext " + oldContextId, exception);
        }
        boolean hasBoundSources = contexts.hasBoundSources(oldContextId);

        if (oldContext.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE) {
            throw new LocationContextReplacementConflictException(oldContextId, "lifecycle is not ACTIVE");
        }
        if (oldContext.revision() != expectedRevision) {
            throw new LocationContextReplacementConflictException(oldContextId, "expected revision is stale");
        }
        final LocationPath oldAnchor;
        try {
            oldAnchor = LocationAnchorPolicy.validateAnchor(
                    oldContext.anchorLocationPath(), oldContext.anchorLocationKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid anchor for active LocationContext " + oldContextId, exception);
        }
        if (hasBoundSources) {
            throw new LocationContextReplacementConflictException(oldContextId, "Sources remain bound");
        }
        if (replacedAtMs < oldContext.updatedAtMs()) {
            throw new IllegalArgumentException(
                    "Replacement timestamp precedes LocationContext " + oldContextId + " update time");
        }
        if (oldContext.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("Revision cannot advance for LocationContext " + oldContextId);
        }

        if (newContext.id().equals(oldContextId)) {
            throw new IllegalArgumentException("Replacement LocationContext must have a different ID");
        }
        if (newContext.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE) {
            throw new IllegalArgumentException("Replacement LocationContext must be ACTIVE");
        }
        LocationPath newAnchor = LocationAnchorPolicy.validateAnchor(
                newContext.anchorLocationPath(), newContext.anchorLocationKey());
        if (!newAnchor.equals(oldAnchor)) {
            throw new IllegalArgumentException("Replacement LocationContext must use the same structured anchor");
        }
        if (newContext.continuityStatus() != LocationContext.ContinuityStatus.REVIEW_REQUIRED
                || newContext.continuityEvidenceJson() != null) {
            throw new IllegalArgumentException("Replacement LocationContext requires review without evidence");
        }
        if (newContext.createdAtMs() != replacedAtMs) {
            throw new IllegalArgumentException("Replacement creation timestamp must equal the transition timestamp");
        }

        for (LocationContext active : contexts.findActive()) {
            if (active.id().equals(oldContextId)) {
                continue;
            }
            final LocationPath activeAnchor;
            try {
                activeAnchor = LocationAnchorPolicy.validateAnchor(
                        active.anchorLocationPath(), active.anchorLocationKey());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid anchor for active LocationContext " + active.id(), exception);
            }
            if (LocationAnchorPolicy.structurallyOverlaps(activeAnchor, newAnchor)) {
                throw new LocationContextStructuralOverlapException(active.id());
            }
        }

        if (contexts.retireActive(oldContextId, expectedRevision, replacedAtMs) != 1) {
            throw new LocationContextReplacementConflictException(oldContextId, "lifecycle or revision changed");
        }
        return contexts.insert(newContext);
    }

    private static void requireCanonicalId(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("LocationContext ID is required");
        }
        try {
            if (!UUID.fromString(contextId).toString().equals(contextId)) {
                throw new IllegalArgumentException("LocationContext ID must be canonical UUID text");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("LocationContext ID must be canonical UUID text", exception);
        }
    }
}
