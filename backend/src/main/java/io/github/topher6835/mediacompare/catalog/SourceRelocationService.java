package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;

/** Explicitly relocates a structured-unbound Source and restores its binding authority. */
@Service
public class SourceRelocationService {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final SourceMembershipRepository memberships;
    private final SourceBindingValidation validation = new SourceBindingValidation();
    private final MacOsApfsLocationContextEvidenceCodec legacyContextCodec =
            new MacOsApfsLocationContextEvidenceCodec();

    public SourceRelocationService(CatalogRepository sources, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, SourceMembershipRepository memberships) {
        this.sources = sources;
        this.contexts = contexts;
        this.periods = periods;
        this.memberships = memberships;
    }

    @Transactional
    public Source relocateAndBind(long sourceId, long expectedSourceLocationRevision,
            String newConfiguredRootPath, String targetContextId, long expectedContextRevision,
            long relocatedAtMs, SourceBindingCapture capture) {
        if (sourceId <= 0 || expectedSourceLocationRevision < 0 || expectedContextRevision < 0
                || relocatedAtMs < 0) {
            throw new IllegalArgumentException("Binding IDs, revisions, and timestamp must be valid");
        }
        if (newConfiguredRootPath == null || newConfiguredRootPath.isBlank()) {
            throw new IllegalArgumentException("New configured Source root is required");
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
            throw new SourceRelocationConflictException(sourceId, "expected Source revision is stale");
        }
        if (context.revision() != expectedContextRevision) {
            throw new SourceRelocationConflictException(sourceId, "expected context revision is stale");
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
            throw new SourceRelocationConflictException(sourceId, "Source revision cannot advance");
        }
        if (relocatedAtMs < source.updatedAtMs() || relocatedAtMs < latest.unboundAtMs()) {
            throw new SourceRelocationConflictException(sourceId, "relocation timestamp regresses");
        }

        var newRoot = LocationPathParser.parse(LocationDialect.UNIX, newConfiguredRootPath);
        String newRootKey = LocationKeyCodec.encode(newRoot).value();
        if (newConfiguredRootPath.equals(source.rootPath()) || newRootKey.equals(source.rootPathKey())) {
            throw new SourceRelocationConflictException(sourceId, "root is unchanged; use rebinding");
        }
        final LocationContextAcceptanceEvidence acceptance;
        try {
            acceptance = LocationContextAcceptanceAuthority.requireCurrentAccepted(context);
        } catch (IllegalArgumentException exception) {
            throw new SourceRelocationConflictException(sourceId,
                    "context is not current ACTIVE + ACCEPTED authority");
        } catch (IllegalStateException exception) {
            if (hasLegacyRawContextEvidence(context.continuityEvidenceJson())) {
                throw new SourceRelocationConflictException(sourceId,
                        "legacy context acceptance lacks current provenance");
            }
            throw exception;
        }
        long relocatedRevision = source.locationRevision() + 1;
        var validated = validation.validate(source, newConfiguredRootPath, context, acceptance,
                capture, relocatedRevision);
        if (!validated.rootKey().equals(newRootKey)) {
            throw new IllegalStateException("Validated new root key disagrees with configured root");
        }

        if (sources.relocateAndBindStructuredSource(source, newConfiguredRootPath, newRootKey,
                targetContextId, validated.evidenceJson(), relocatedAtMs) != 1) {
            throw new SourceRelocationConflictException(sourceId, "Source binding state changed");
        }
        Source bound = sources.findSourceById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Relocated Source " + sourceId + " disappeared"));
        Source expected = new Source(source.id(), source.name(), newConfiguredRootPath, newRootKey,
                relocatedRevision, source.rootPathDialect(), targetContextId, validated.evidenceJson(),
                source.createdAtMs(), relocatedAtMs);
        if (!expected.equals(bound)) {
            throw new IllegalStateException("Relocated Source does not match expected bound snapshot");
        }
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
            throw new SourceRelocationConflictException(source.id(), "Source is already bound");
        }
        if (source.rootPathDialect() == null && source.bindingEvidenceJson() == null) {
            if (!Objects.equals(source.rootPathKey(), source.rootPath())) {
                throw new IllegalStateException("Source " + source.id() + " has a partial structured root");
            }
            throw new SourceRelocationConflictException(source.id(), "Source was never bound");
        }
        if (source.bindingEvidenceJson() != null
                || !LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())) {
            throw new IllegalStateException("Source " + source.id() + " is not structurally unbound");
        }
        try {
            var oldRoot = LocationPathParser.parse(LocationDialect.UNIX, source.rootPath());
            if (!LocationKeyCodec.matches(oldRoot, LocationKey.parse(source.rootPathKey()))) {
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
