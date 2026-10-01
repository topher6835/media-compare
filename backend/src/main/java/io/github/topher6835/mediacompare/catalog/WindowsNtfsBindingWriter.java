package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.filesystem.WindowsNtfsContextEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsSourceEvidence;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.LocationKey;

/** Short writer-reserved NTFS acceptance and first-binding transitions. */
@Service
public class WindowsNtfsBindingWriter {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final SourceMembershipRepository memberships;
    private final WindowsNtfsEvidenceCodec codec = new WindowsNtfsEvidenceCodec();

    public WindowsNtfsBindingWriter(CatalogRepository sources, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, SourceMembershipRepository memberships) {
        this.sources = sources;
        this.contexts = contexts;
        this.periods = periods;
        this.memberships = memberships;
    }

    @Transactional
    public LocationContext accept(String contextId, long expectedRevision, WindowsNtfsIdentity anchorIdentity,
            long acceptedAtMs) {
        contexts.reserveWrite();
        LocationContext context = contexts.findById(contextId).orElseThrow();
        if (context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.REVIEW_REQUIRED
                || context.continuityEvidenceJson() != null || context.revision() != expectedRevision
                || contexts.hasBoundSources(contextId) || expectedRevision == Long.MAX_VALUE
                || acceptedAtMs < context.updatedAtMs()) {
            throw new LocationContextAcceptanceConflictException(contextId, "NTFS acceptance state changed");
        }
        LocationPath anchor = io.github.topher6835.mediacompare.location.LocationAnchorPolicy
                .validateAnchor(context.anchorLocationPath(), context.anchorLocationKey());
        String evidence = codec.encode(new WindowsNtfsContextEvidence(contextId,
                expectedRevision + 1, anchor, anchorIdentity));
        if (contexts.acceptReviewRequired(contextId, expectedRevision, acceptedAtMs, evidence) != 1) {
            throw new LocationContextAcceptanceConflictException(contextId, "NTFS acceptance changed");
        }
        return contexts.findById(contextId).orElseThrow();
    }

    @Transactional
    public Source bind(long sourceId, long expectedSourceRevision, String expectedRootPath,
            String contextId, long expectedContextRevision, WindowsNtfsIdentity contextObserved,
            WindowsNtfsIdentity rootObserved, long boundAtMs) {
        contexts.reserveWrite();
        Source source = sources.findSourceById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source " + sourceId + " does not exist"));
        LocationContext context = contexts.findById(contextId).orElseThrow();
        if (source.locationRevision() != expectedSourceRevision
                || expectedSourceRevision == Long.MAX_VALUE || !source.rootPath().equals(expectedRootPath)
                || !source.rootPathKey().equals(expectedRootPath)
                || source.rootPathDialect() != null || source.boundLocationContextId() != null
                || source.bindingEvidenceJson() != null || context.revision() != expectedContextRevision
                || context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED
                || boundAtMs < source.updatedAtMs()) {
            throw new SourceBindingConflictException(sourceId, "NTFS Source or context state changed");
        }
        var baseline = codec.decodeContext(context.continuityEvidenceJson());
        LocationPath anchor = io.github.topher6835.mediacompare.location.LocationAnchorPolicy
                .validateAnchor(context.anchorLocationPath(), context.anchorLocationKey());
        LocationPath root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
        if (!baseline.contextId().equals(contextId) || baseline.contextRevision() != context.revision()
                || !baseline.anchor().equals(anchor) || !baseline.identity().equals(contextObserved)
                || !anchor.contains(root)
                || !contextObserved.volumeSerial().equals(rootObserved.volumeSerial())) {
            throw new IllegalArgumentException("NTFS binding observation disagrees with accepted context");
        }
        String key = LocationKeyCodec.encode(root).value();
        String evidence = codec.encode(new WindowsNtfsSourceEvidence(sourceId,
                expectedSourceRevision + 1, contextId, context.revision(), root, rootObserved));
        if (sources.bindUnboundSource(sourceId, expectedSourceRevision, expectedRootPath,
                LocationDialect.WINDOWS_DRIVE.persistedName(), key, contextId, evidence, boundAtMs) != 1) {
            throw new SourceBindingConflictException(sourceId, "NTFS Source binding changed");
        }
        Source bound = sources.findSourceById(sourceId).orElseThrow();
        CurrentLocationAuthority.requirePersisted(bound, context);
        periods.insertOpen(new SourceBindingPeriod(null, bound.id(), bound.locationRevision(),
                bound.boundLocationContextId(), bound.rootPathDialect(), bound.rootPath(),
                bound.rootPathKey(), bound.bindingEvidenceJson(), bound.updatedAtMs(), null, null));
        return bound;
    }

    @Transactional
    public Source rebind(long sourceId, long expectedSourceRevision, String contextId,
            long expectedContextRevision, WindowsNtfsIdentity contextObserved,
            WindowsNtfsIdentity rootObserved, long reboundAtMs) {
        contexts.reserveWrite();
        Source source = sources.findSourceById(sourceId).orElseThrow();
        LocationContext context = contexts.findById(contextId).orElseThrow();
        if (source.locationRevision() != expectedSourceRevision || expectedSourceRevision == Long.MAX_VALUE
                || source.boundLocationContextId() != null || source.bindingEvidenceJson() != null
                || !LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())
                || context.revision() != expectedContextRevision
                || context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED
                || reboundAtMs < source.updatedAtMs()) {
            throw new SourceRebindingConflictException(sourceId, "NTFS rebinding state changed");
        }
        LocationPath root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
        if (!LocationKeyCodec.matches(root, LocationKey.parse(source.rootPathKey()))
                || periods.findOpenBySourceId(sourceId).isPresent()
                || memberships.countActiveBySourceId(sourceId) != 0) {
            throw new IllegalStateException("NTFS Source has inconsistent unbound state");
        }
        SourceBindingPeriod latest = periods.findLatestBySourceId(sourceId).orElseThrow();
        if (latest.sourceId() != sourceId || latest.unboundSourceLocationRevision() == null
                || latest.unboundAtMs() == null
                || latest.unboundSourceLocationRevision() != source.locationRevision()
                || latest.unboundAtMs() != source.updatedAtMs()
                || !Objects.equals(latest.rootPathDialect(), source.rootPathDialect())
                || !Objects.equals(latest.rootPath(), source.rootPath())
                || !Objects.equals(latest.rootPathKey(), source.rootPathKey())) {
            throw new IllegalStateException("NTFS closed binding period disagrees with Source");
        }
        var baseline = codec.decodeContext(context.continuityEvidenceJson());
        LocationPath anchor = io.github.topher6835.mediacompare.location.LocationAnchorPolicy
                .validateAnchor(context.anchorLocationPath(), context.anchorLocationKey());
        if (!baseline.contextId().equals(contextId) || baseline.contextRevision() != context.revision()
                || !baseline.anchor().equals(anchor) || !baseline.identity().equals(contextObserved)
                || !anchor.contains(root)
                || !contextObserved.volumeSerial().equals(rootObserved.volumeSerial())) {
            throw new IllegalArgumentException("NTFS rebinding observation disagrees with accepted context");
        }
        String evidence = codec.encode(new WindowsNtfsSourceEvidence(sourceId,
                expectedSourceRevision + 1, contextId, context.revision(), root, rootObserved));
        if (sources.rebindStructuredSource(source, contextId, evidence, reboundAtMs) != 1) {
            throw new SourceRebindingConflictException(sourceId, "NTFS Source rebinding changed");
        }
        Source bound = sources.findSourceById(sourceId).orElseThrow();
        CurrentLocationAuthority.requirePersisted(bound, context);
        periods.insertOpen(new SourceBindingPeriod(null, bound.id(), bound.locationRevision(),
                bound.boundLocationContextId(), bound.rootPathDialect(), bound.rootPath(),
                bound.rootPathKey(), bound.bindingEvidenceJson(), bound.updatedAtMs(), null, null));
        return bound;
    }
}
