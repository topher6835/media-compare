package io.github.topher6835.mediacompare.catalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityScope;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatContextEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatSourceEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatVolumeEvidence;
import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;

/** Short writer-reserved transactions. Native acquisition/revalidation and the outer gate belong to the caller. */
@Service
public class WindowsExfatBindingWriter {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final SourceMembershipRepository memberships;
    private final LocationContextActivationService activation;
    private final WindowsExfatEvidenceCodec codec = new WindowsExfatEvidenceCodec();
    private final LocationPathCodec paths = new LocationPathCodec();

    public WindowsExfatBindingWriter(CatalogRepository sources, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, SourceMembershipRepository memberships,
            LocationContextActivationService activation) {
        this.sources = sources; this.contexts = contexts; this.periods = periods;
        this.memberships = memberships; this.activation = activation;
    }

    @Transactional
    public ExfatAuthorityScope firstBind(Source expected, LocationPath resolved,
            WindowsExfatVolumeEvidence volume, long now) {
        contexts.reserveWrite();
        Source source = unchanged(expected);
        if (SourcePreparationState.from(source) != SourcePreparationState.PREPARATION_REQUIRED
                || source.locationRevision() == Long.MAX_VALUE || now < source.updatedAtMs()
                || periods.findOpenBySourceId(source.id()).isPresent()
                || !periods.findBySourceId(source.id()).isEmpty() || memberships.countActiveBySourceId(source.id()) != 0) {
            throw conflict();
        }
        LocationPath anchor = new LocationPath(LocationDialect.WINDOWS_DRIVE, resolved.rootFields(), List.of());
        LocationContext context = null;
        for (LocationContext candidate : contexts.findActive()) {
            LocationPath existing = LocationAnchorPolicy.validateAnchor(candidate.anchorLocationPath(), candidate.anchorLocationKey());
            if (existing.dialect() == LocationDialect.WINDOWS_DRIVE
                    && existing.rootFields().getFirst().equalsIgnoreCase(anchor.rootFields().getFirst())) {
                if (!existing.equals(anchor) || context != null) throw conflict();
                context = candidate;
            }
        }
        if (context == null) {
            context = activation.createActive(new LocationContext(UUID.randomUUID().toString(), paths.encode(anchor),
                    LocationKeyCodec.encode(anchor).value(), LocationContext.LifecycleStatus.ACTIVE,
                    LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, now, now));
        }
        if (context.continuityStatus() == LocationContext.ContinuityStatus.REVIEW_REQUIRED) {
            if (context.continuityEvidenceJson() != null || contexts.hasBoundSources(context.id())
                    || context.revision() == Long.MAX_VALUE || now < context.updatedAtMs()) throw conflict();
            String evidence = codec.encode(new WindowsExfatContextEvidence(WindowsExfatContextEvidence.VERSION,
                    context.id(), context.revision() + 1, paths.encode(anchor), LocationKeyCodec.encode(anchor).value(),
                    volume, "EXPLICIT_PREPARE_ACCEPT", now));
            if (contexts.acceptReviewRequired(context.id(), context.revision(), now, evidence) != 1) throw conflict();
            context = contexts.findById(context.id()).orElseThrow();
        }
        requireContext(context, volume, anchor);
        return bind(source, context, resolved, volume, now, false);
    }

    /** Explicit fixed-root true rebind only; never called by routine Prepare. */
    @Transactional
    public ExfatAuthorityScope rebind(Source expected, String contextId, LocationPath resolved,
            WindowsExfatVolumeEvidence volume, long now) {
        contexts.reserveWrite();
        Source source = unchanged(expected);
        if (SourcePreparationState.from(source) != SourcePreparationState.REBIND_REQUIRED
                || !LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())
                || source.locationRevision() == Long.MAX_VALUE || now < source.updatedAtMs()
                || periods.findOpenBySourceId(source.id()).isPresent()
                || memberships.countActiveBySourceId(source.id()) != 0) throw conflict();
        SourceBindingPeriod previous = periods.findLatestBySourceId(source.id()).orElseThrow();
        if (!Objects.equals(previous.unboundSourceLocationRevision(), source.locationRevision())
                || !Objects.equals(previous.unboundAtMs(), source.updatedAtMs())
                || !previous.rootPath().equals(source.rootPath())
                || !previous.rootPathKey().equals(source.rootPathKey())
                || !previous.rootPathDialect().equals(source.rootPathDialect())) throw conflict();
        LocationContext context = contexts.findById(contextId).orElseThrow();
        requireContext(context, volume, new LocationPath(LocationDialect.WINDOWS_DRIVE, resolved.rootFields(), List.of()));
        return bind(source, context, resolved, volume, now, true);
    }

    private ExfatAuthorityScope bind(Source source, LocationContext context, LocationPath resolved,
            WindowsExfatVolumeEvidence volume, long now, boolean rebind) {
        String key = LocationKeyCodec.encode(io.github.topher6835.mediacompare.location.LocationPathParser
                .parse(LocationDialect.WINDOWS_DRIVE, source.rootPath())).value();
        if (rebind && !key.equals(source.rootPathKey())) throw conflict();
        String evidence = codec.encode(new WindowsExfatSourceEvidence(WindowsExfatSourceEvidence.VERSION,
                source.id(), source.locationRevision() + 1, context.id(), context.revision(), source.rootPath(), key,
                paths.encode(resolved), LocationKeyCodec.encode(resolved).value(), volume, true, true, "EXPLICIT_PREPARE_BIND", now));
        int changed = rebind ? sources.rebindStructuredSource(source, context.id(), evidence, now)
                : sources.bindUnboundSource(source.id(), source.locationRevision(), source.rootPath(),
                    LocationDialect.WINDOWS_DRIVE.persistedName(), key, context.id(), evidence, now);
        if (changed != 1) throw conflict();
        Source bound = sources.findSourceById(source.id()).orElseThrow();
        CurrentLocationAuthority.requirePersisted(bound, context);
        SourceBindingPeriod period = periods.insertOpen(new SourceBindingPeriod(null, bound.id(), bound.locationRevision(),
                context.id(), bound.rootPathDialect(), bound.rootPath(), bound.rootPathKey(), bound.bindingEvidenceJson(),
                bound.updatedAtMs(), null, null));
        return new ExfatAuthorityScope(bound, context, period);
    }

    private void requireContext(LocationContext context, WindowsExfatVolumeEvidence volume, LocationPath anchor) {
        if (context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED) throw conflict();
        var evidence = codec.decodeContext(context.continuityEvidenceJson());
        if (!evidence.contextId().equals(context.id()) || evidence.contextRevision() != context.revision()
                || !evidence.anchorLocationPath().equals(context.anchorLocationPath())
                || !evidence.anchorLocationKey().equals(context.anchorLocationKey())
                || !paths.decode(evidence.anchorLocationPath()).equals(anchor) || !evidence.volume().equals(volume)) throw conflict();
    }

    /** Writer reservation plus exact reread; intentionally performs no durable update. */
    @Transactional
    public ExfatAuthorityScope reread(ExfatAuthorityScope expected) {
        contexts.reserveWrite();
        ExfatAuthorityScope current = snapshot(expected.source().id());
        if (!current.equals(expected)) throw conflict();
        return current;
    }

    public ExfatAuthorityScope snapshot(long sourceId) {
        Source source = sources.findSourceById(sourceId).orElseThrow();
        return new ExfatAuthorityScope(source, contexts.findById(source.boundLocationContextId()).orElseThrow(),
                periods.findOpenBySourceId(sourceId).orElseThrow());
    }

    private Source unchanged(Source expected) {
        Source current = sources.findSourceById(expected.id()).orElseThrow();
        if (!current.equals(expected)) throw conflict();
        return current;
    }
    private static SourcePreparationException conflict() {
        return new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
    }
}
