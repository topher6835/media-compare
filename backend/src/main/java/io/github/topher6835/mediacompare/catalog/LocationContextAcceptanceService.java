package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;

/** Persists an already captured successful APFS observation as context acceptance. */
@Service
public class LocationContextAcceptanceService {
    private final LocationContextRepository contexts;
    private final LocationContextAcceptanceEvidenceCodec codec = new LocationContextAcceptanceEvidenceCodec();

    public LocationContextAcceptanceService(LocationContextRepository contexts) {
        this.contexts = contexts;
    }

    @Transactional
    public LocationContext acceptReviewRequired(String contextId, long expectedRevision, long acceptedAtMs,
            ContinuityProbeResult<MacOsApfsLocationContextEvidence> acceptedProbeResult) {
        requireCanonicalId(contextId);
        if (expectedRevision < 0 || acceptedAtMs < 0) {
            throw new IllegalArgumentException("Expected revision and acceptance timestamp must not be negative");
        }
        if (acceptedProbeResult == null) {
            throw new IllegalArgumentException("Accepted probe result is required");
        }

        contexts.reserveWrite();
        final LocationContext existing;
        try {
            existing = contexts.findById(contextId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "LocationContext " + contextId + " does not exist"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid persisted LocationContext " + contextId, exception);
        }
        boolean hasBoundSources = contexts.hasBoundSources(contextId);

        if (existing.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE) {
            throw new LocationContextAcceptanceConflictException(contextId, "lifecycle is not ACTIVE");
        }
        if (existing.continuityStatus() != LocationContext.ContinuityStatus.REVIEW_REQUIRED) {
            throw new LocationContextAcceptanceConflictException(contextId, "continuity is already ACCEPTED");
        }
        if (existing.revision() != expectedRevision) {
            throw new LocationContextAcceptanceConflictException(contextId, "expected revision is stale");
        }
        final LocationPath anchor;
        try {
            anchor = LocationAnchorPolicy.validateAnchor(
                    existing.anchorLocationPath(), existing.anchorLocationKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid anchor for active LocationContext " + contextId, exception);
        }
        if (acceptedAtMs < existing.updatedAtMs()) {
            throw new IllegalArgumentException("Acceptance timestamp precedes LocationContext " + contextId
                    + " update time");
        }
        if (existing.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("Revision cannot advance for LocationContext " + contextId);
        }
        if (hasBoundSources) {
            throw new LocationContextAcceptanceConflictException(contextId, "Sources remain bound");
        }
        if (acceptedProbeResult.outcome() != ContinuityOutcome.ACCEPTED
                || acceptedProbeResult.evidence().isEmpty()) {
            throw new IllegalArgumentException("A successful accepted APFS probe result is required");
        }
        MacOsApfsLocationContextEvidence evidence = acceptedProbeResult.evidence().orElseThrow();
        if (!evidence.anchorLocationPath().equals(anchor)
                || !evidence.anchorLocationKey().value().equals(existing.anchorLocationKey())
                || !evidence.directory() || evidence.symbolicLink()) {
            throw new IllegalArgumentException("Accepted APFS evidence does not match the context anchor");
        }

        long acceptedRevision = existing.revision() + 1;
        String encoded = codec.encode(new LocationContextAcceptanceEvidence(
                LocationContextAcceptanceEvidence.VERSION, contextId, acceptedRevision, evidence));
        if (contexts.acceptReviewRequired(contextId, expectedRevision, acceptedAtMs, encoded) != 1) {
            throw new LocationContextAcceptanceConflictException(contextId, "continuity or revision changed");
        }
        final LocationContext accepted;
        try {
            accepted = contexts.findById(contextId)
                    .orElseThrow(() -> new IllegalStateException("Accepted LocationContext " + contextId
                            + " disappeared"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid persisted LocationContext " + contextId, exception);
        }
        LocationContextAcceptanceAuthority.requireCurrentAccepted(accepted);
        return accepted;
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
