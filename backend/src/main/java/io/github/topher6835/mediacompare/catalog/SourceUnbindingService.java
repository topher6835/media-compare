package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicitly withdraws one Source's current binding without observing the filesystem. */
@Service
public class SourceUnbindingService {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final SourceBindingPeriodRepository periods;
    private final SourceMembershipRepository memberships;

    public SourceUnbindingService(CatalogRepository sources, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, SourceMembershipRepository memberships) {
        this.sources = sources;
        this.contexts = contexts;
        this.periods = periods;
        this.memberships = memberships;
    }

    @Transactional
    public Source unbind(long sourceId, long expectedSourceLocationRevision, long unboundAtMs) {
        if (sourceId <= 0 || expectedSourceLocationRevision < 0 || unboundAtMs < 0) {
            throw new IllegalArgumentException("Source ID, revision, and timestamp must be valid");
        }

        contexts.reserveWrite();
        Source source = sources.findSourceById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source " + sourceId + " does not exist"));
        if (source.locationRevision() != expectedSourceLocationRevision) {
            throw new SourceUnbindingConflictException(sourceId, "expected Source revision is stale");
        }
        if (source.boundLocationContextId() == null) {
            if (source.bindingEvidenceJson() != null) {
                throw new IllegalStateException("Unbound Source " + sourceId + " has binding evidence");
            }
            throw new SourceUnbindingConflictException(sourceId, "Source is already or never bound");
        }
        SourceBindingPeriod period = periods.findOpenBySourceId(sourceId)
                .orElseThrow(() -> new IllegalStateException(
                        "Bound Source " + sourceId + " has no open binding period"));
        if (period.id() == null || period.sourceId() != sourceId
                || period.boundSourceLocationRevision() != source.locationRevision()
                || !Objects.equals(period.locationContextId(), source.boundLocationContextId())
                || !Objects.equals(period.rootPathDialect(), source.rootPathDialect())
                || !Objects.equals(period.rootPath(), source.rootPath())
                || !Objects.equals(period.rootPathKey(), source.rootPathKey())
                || !Objects.equals(period.bindingEvidenceJson(), source.bindingEvidenceJson())
                || period.unboundSourceLocationRevision() != null || period.unboundAtMs() != null
                || period.boundAtMs() > source.updatedAtMs()) {
            throw new IllegalStateException("Open binding period disagrees with Source " + sourceId);
        }
        if (source.locationRevision() == Long.MAX_VALUE) {
            throw new SourceUnbindingConflictException(sourceId, "Source revision cannot advance");
        }
        if (unboundAtMs < source.updatedAtMs() || unboundAtMs < period.boundAtMs()) {
            throw new SourceUnbindingConflictException(sourceId, "unbinding timestamp regresses");
        }

        long unboundRevision = source.locationRevision() + 1;
        if (periods.closeOpen(period, unboundRevision, unboundAtMs) != 1) {
            throw new IllegalStateException("Open binding period changed for Source " + sourceId);
        }
        int activeCount = memberships.countActiveBySourceId(sourceId);
        if (memberships.retireActiveBySourceId(sourceId) != activeCount) {
            throw new IllegalStateException("Active memberships changed for Source " + sourceId);
        }
        if (sources.unbindSource(source, unboundAtMs) != 1) {
            throw new SourceUnbindingConflictException(sourceId, "Source binding state changed");
        }
        Source unbound = sources.findSourceById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Unbound Source " + sourceId + " disappeared"));
        Source expected = new Source(source.id(), source.name(), source.rootPath(), source.rootPathKey(),
                unboundRevision, source.rootPathDialect(), null, null,
                source.createdAtMs(), unboundAtMs);
        if (!expected.equals(unbound)) {
            throw new IllegalStateException("Source " + sourceId + " did not reach structured-unbound state");
        }
        return unbound;
    }
}
