package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;

/** Explicitly restores current authority to a structured-unbound Source. */
@Service
public class SourceRebindingService {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final SourceMembershipRepository memberships;
    private final SourceBindingValidation validation = new SourceBindingValidation();
    private final MacOsApfsLocationContextEvidenceCodec legacyContextCodec =
            new MacOsApfsLocationContextEvidenceCodec();

    public SourceRebindingService(CatalogRepository sources, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, SourceMembershipRepository memberships) {
        this.sources = sources;
        this.contexts = contexts;
        this.periods = periods;
        this.memberships = memberships;
    }

    @Transactional
    public Source rebind(long sourceId, long expectedSourceLocationRevision, String targetContextId,
            long expectedContextRevision, long reboundAtMs, SourceBindingCapture capture) {
        if (sourceId <= 0 || expectedSourceLocationRevision < 0 || expectedContextRevision < 0
                || reboundAtMs < 0) {
            throw new IllegalArgumentException("Binding IDs, revisions, and timestamp must be valid");
        }
        requireCanonicalContextId(targetContextId);
        if (capture == null || capture.contextProbeResult() == null || capture.sourceRootProbeResult() == null
                || capture.configuredRootPathSnapshot() == null) {
            throw new IllegalArgumentException("Complete typed Source binding capture is required");
        }

        contexts.reserveWrite();
        Source source = sources.findSourceById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source " + sourceId + " does not exist"));
        final LocationContext context;
        try {
            context = contexts.findById(targetContextId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "LocationContext " + targetContextId + " does not exist"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid persisted LocationContext " + targetContextId, exception);
        }

        if (source.locationRevision() != expectedSourceLocationRevision) {
            throw new SourceRebindingConflictException(sourceId, "expected Source revision is stale");
        }
        if (context.revision() != expectedContextRevision) {
            throw new SourceRebindingConflictException(sourceId, "expected context revision is stale");
        }
        requireStructuredUnbound(source);
        if (periods.findOpenBySourceId(sourceId).isPresent()) {
            throw new IllegalStateException("Structured-unbound Source " + sourceId + " has an open period");
        }
        SourceBindingPeriod latest = periods.findLatestBySourceId(sourceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Structured-unbound Source " + sourceId + " has no closed period"));
        if (latest.sourceId() != sourceId || latest.unboundSourceLocationRevision() == null
                || latest.unboundAtMs() == null
                || latest.unboundSourceLocationRevision() != source.locationRevision()
                || latest.unboundAtMs() != source.updatedAtMs()
                || !Objects.equals(latest.rootPathDialect(), source.rootPathDialect())
                || !Objects.equals(latest.rootPath(), source.rootPath())
                || !Objects.equals(latest.rootPathKey(), source.rootPathKey())) {
            throw new IllegalStateException("Latest closed binding period disagrees with Source " + sourceId);
        }
        if (memberships.countActiveBySourceId(sourceId) != 0) {
            throw new IllegalStateException("Structured-unbound Source " + sourceId + " has ACTIVE memberships");
        }
        if (source.locationRevision() == Long.MAX_VALUE) {
            throw new SourceRebindingConflictException(sourceId, "Source revision cannot advance");
        }
        if (reboundAtMs < source.updatedAtMs() || reboundAtMs < latest.unboundAtMs()) {
            throw new SourceRebindingConflictException(sourceId, "rebinding timestamp regresses");
        }

        final LocationContextAcceptanceEvidence acceptance;
        try {
            acceptance = LocationContextAcceptanceAuthority.requireCurrentAccepted(context);
        } catch (IllegalArgumentException exception) {
            throw new SourceRebindingConflictException(sourceId,
                    "context is not current ACTIVE + ACCEPTED authority");
        } catch (IllegalStateException exception) {
            if (hasLegacyRawContextEvidence(context.continuityEvidenceJson())) {
                throw new SourceRebindingConflictException(sourceId,
                        "legacy context acceptance lacks current provenance");
            }
            throw exception;
        }
        long reboundRevision = source.locationRevision() + 1;
        var validated = validation.validate(source, context, acceptance, capture, reboundRevision);
        if (!validated.rootKey().equals(source.rootPathKey())) {
            throw new IllegalArgumentException("Rebinding cannot change the Source root key");
        }

        if (sources.rebindStructuredSource(source, targetContextId,
                validated.evidenceJson(), reboundAtMs) != 1) {
            throw new SourceRebindingConflictException(sourceId, "Source binding state changed");
        }
        Source bound = sources.findSourceById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Rebound Source " + sourceId + " disappeared"));
        SourceBindingAuthority.requireCurrentBound(bound);
        periods.insertOpen(new SourceBindingPeriod(null, bound.id(), bound.locationRevision(),
                bound.boundLocationContextId(), bound.rootPathDialect(), bound.rootPath(),
                bound.rootPathKey(), bound.bindingEvidenceJson(), bound.updatedAtMs(), null, null));
        return bound;
    }

    private static void requireStructuredUnbound(Source source) {
        if (source.boundLocationContextId() != null) {
            if (source.bindingEvidenceJson() == null || source.rootPathDialect() == null) {
                throw new IllegalStateException("Source " + source.id() + " has a partial current binding");
            }
            throw new SourceRebindingConflictException(source.id(), "Source is already bound");
        }
        if (source.rootPathDialect() == null && source.bindingEvidenceJson() == null) {
            if (!Objects.equals(source.rootPathKey(), source.rootPath())) {
                throw new IllegalStateException("Source " + source.id() + " has a partial structured root");
            }
            throw new SourceRebindingConflictException(source.id(), "Source was never bound");
        }
        if (source.bindingEvidenceJson() != null
                || !LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())) {
            throw new IllegalStateException("Source " + source.id() + " is not structurally unbound");
        }
        try {
            var root = LocationPathParser.parse(LocationDialect.UNIX, source.rootPath());
            if (!LocationKeyCodec.matches(root, LocationKey.parse(source.rootPathKey()))) {
                throw new IllegalArgumentException("Structured root path and key disagree");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid structured root for Source " + source.id(), exception);
        }
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
