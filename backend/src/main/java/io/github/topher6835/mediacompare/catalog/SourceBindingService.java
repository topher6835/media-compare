package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;

/** First-time binding of an existing legacy Source to current accepted APFS context authority. */
@Service
public class SourceBindingService {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final SourceBindingValidation validation = new SourceBindingValidation();
    private final MacOsApfsLocationContextEvidenceCodec legacyContextCodec =
            new MacOsApfsLocationContextEvidenceCodec();

    public SourceBindingService(CatalogRepository sources, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods) {
        this.sources = sources;
        this.contexts = contexts;
        this.periods = periods;
    }

    @Transactional
    public Source bindUnboundSource(long sourceId, long expectedSourceRevision, String contextId,
            long expectedContextRevision, long boundAtMs, SourceBindingCapture capture) {
        if (sourceId <= 0 || expectedSourceRevision < 0 || expectedContextRevision < 0 || boundAtMs < 0) {
            throw new IllegalArgumentException("Binding IDs, revisions, and timestamp must be valid");
        }
        requireCanonicalContextId(contextId);
        if (capture == null || capture.contextProbeResult() == null || capture.sourceRootProbeResult() == null
                || capture.configuredRootPathSnapshot() == null) {
            throw new IllegalArgumentException("Complete typed Source binding capture is required");
        }
        if (expectedSourceRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Source revision cannot advance");
        }

        contexts.reserveWrite();
        Source source = sources.findSourceById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source " + sourceId + " does not exist"));
        final LocationContext context;
        try {
            context = contexts.findById(contextId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "LocationContext " + contextId + " does not exist"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid persisted LocationContext " + contextId, exception);
        }

        if (source.locationRevision() != expectedSourceRevision) {
            throw new SourceBindingConflictException(sourceId, "expected Source revision is stale");
        }
        if (context.revision() != expectedContextRevision) {
            throw new SourceBindingConflictException(sourceId, "expected context revision is stale");
        }
        if (source.boundLocationContextId() != null || source.rootPathDialect() != null
                || source.bindingEvidenceJson() != null || !Objects.equals(source.rootPathKey(), source.rootPath())) {
            throw new SourceBindingConflictException(sourceId, "Source is not legacy and unbound");
        }
        if (boundAtMs < source.updatedAtMs()) {
            throw new IllegalArgumentException("Binding timestamp precedes Source update time");
        }

        final LocationContextAcceptanceEvidence acceptance;
        try {
            acceptance = LocationContextAcceptanceAuthority.requireCurrentAccepted(context);
        } catch (IllegalArgumentException exception) {
            throw new SourceBindingConflictException(sourceId, "context is not current ACTIVE + ACCEPTED authority");
        } catch (IllegalStateException exception) {
            if (hasLegacyRawContextEvidence(context.continuityEvidenceJson())) {
                throw new SourceBindingConflictException(sourceId, "legacy context acceptance lacks current provenance");
            }
            throw exception;
        }
        long boundRevision = expectedSourceRevision + 1;
        var validated = validation.validate(source, context, acceptance, capture, boundRevision);
        if (sources.bindUnboundSource(sourceId, expectedSourceRevision, source.rootPath(),
                validated.rootKey(), contextId, validated.evidenceJson(), boundAtMs) != 1) {
            throw new SourceBindingConflictException(sourceId, "Source binding state changed");
        }
        Source bound = sources.findSourceById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Bound Source " + sourceId + " disappeared"));
        SourceBindingAuthority.requireCurrentBound(bound);
        periods.insertOpen(new SourceBindingPeriod(null, bound.id(), bound.locationRevision(),
                bound.boundLocationContextId(), bound.rootPathDialect(), bound.rootPath(),
                bound.rootPathKey(), bound.bindingEvidenceJson(), bound.updatedAtMs(), null, null));
        return bound;
    }

    private boolean hasLegacyRawContextEvidence(String evidenceJson) {
        try {
            legacyContextCodec.decode(evidenceJson);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void requireCanonicalContextId(String contextId) {
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
