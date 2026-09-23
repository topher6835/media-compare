package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;

@Service
public class LocationContextRetirementService {
    private final LocationContextRepository contexts;

    public LocationContextRetirementService(LocationContextRepository contexts) {
        this.contexts = contexts;
    }

    @Transactional
    public LocationContext retireActive(String contextId, long expectedRevision, long retiredAtMs) {
        requireCanonicalId(contextId);
        if (expectedRevision < 0 || retiredAtMs < 0) {
            throw new IllegalArgumentException("Expected revision and retirement timestamp must not be negative");
        }

        contexts.reserveWrite();
        final LocationContext existing;
        try {
            existing = contexts.findById(contextId)
                    .orElseThrow(() -> new NoSuchElementException("LocationContext " + contextId + " does not exist"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid persisted LocationContext " + contextId, exception);
        }
        boolean hasBoundSources = contexts.hasBoundSources(contextId);

        if (existing.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE) {
            throw new LocationContextRetirementConflictException(contextId, "lifecycle is not ACTIVE");
        }
        if (existing.revision() != expectedRevision) {
            throw new LocationContextRetirementConflictException(contextId, "expected revision is stale");
        }
        try {
            LocationAnchorPolicy.validateAnchor(existing.anchorLocationPath(), existing.anchorLocationKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid anchor for active LocationContext " + contextId, exception);
        }
        if (hasBoundSources) {
            throw new LocationContextRetirementConflictException(contextId, "Sources remain bound");
        }
        if (retiredAtMs < existing.updatedAtMs()) {
            throw new IllegalArgumentException("Retirement timestamp precedes LocationContext " + contextId + " update time");
        }
        if (existing.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("Revision cannot advance for LocationContext " + contextId);
        }

        if (contexts.retireActive(contextId, expectedRevision, retiredAtMs) != 1) {
            throw new LocationContextRetirementConflictException(contextId, "lifecycle or revision changed");
        }
        return contexts.findById(contextId)
                .orElseThrow(() -> new IllegalStateException("Retired LocationContext " + contextId + " disappeared"));
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
