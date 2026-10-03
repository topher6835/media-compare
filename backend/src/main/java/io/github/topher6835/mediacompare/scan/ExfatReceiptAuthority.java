package io.github.topher6835.mediacompare.scan;

import java.util.Objects;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.OccurrenceProfileValidation;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import org.springframework.stereotype.Component;

/** Strict receipt/current-catalog checks shared by the assignment and SHA writers. No host IO. */
@Component
public class ExfatReceiptAuthority {
    private final ExfatScanBundles bundles;
    private final SourceMembershipRepository memberships;
    private final CatalogRepository catalog;
    private final LocationContextRepository contexts;

    public ExfatReceiptAuthority(ExfatScanBundles bundles, SourceMembershipRepository memberships,
            CatalogRepository catalog, LocationContextRepository contexts) {
        this.bundles = bundles; this.memberships = memberships; this.catalog = catalog; this.contexts = contexts;
    }

    /** Requires the caller's outer exact-window lease and transaction. */
    public ExfatObservationReceipt reserveAndRequire(FileEntry file, long scanRunId, String stage) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Receipt processing requires a catalog writer transaction");
        }
        contexts.reserveWrite();
        if (!memberships.findById(file.id()).orElseThrow().equals(file)) throw integrity();
        var receipt = OccurrenceProfileValidation.requireExfat(file);
        var path = new LocationPathCodec().decode(receipt.locationPath());
        var qualifying = bundles.authorities(scanRunId).stream().filter(a -> a.scope().context().id().equals(receipt.contextId())
                && a.root().contains(path)).toList();
        if (receipt.scanRunId() != scanRunId || qualifying.size() != receipt.sources().size()) throw integrity();
        for (var source : receipt.sources()) {
            var a = bundles.require(scanRunId, source.sourceId());
            bundles.requireCatalog(a, stage, "COMPLETED");
            if (!a.bundleUuid().equals(receipt.bundleUuid()) || a.jobId() != receipt.scanJobId()
                    || !a.window().windowId().equals(source.windowUuid()) || a.scanRunSourceId() != source.scanRunSourceId()
                    || a.generation() != source.generation() || a.scope().source().locationRevision() != source.sourceRevision()
                    || !a.scope().context().id().equals(receipt.contextId()) || a.scope().context().revision() != receipt.contextRevision()
                    || !new LocationPathCodec().encode(a.root()).equals(source.rootPath())
                    || !LocationKeyCodec.encode(a.root()).value().equals(source.rootPathKey())) throw integrity();
            var member = memberships.findMembershipById(source.membershipId()).orElseThrow();
            if (member.fileEntryId() != file.id() || member.sourceId() != source.sourceId()
                    || !"ACTIVE".equals(member.applicabilityStatus()) || !"PRESENT".equals(member.presenceStatus())
                    || member.membershipRevision() != source.membershipRevision() || member.observedFileEntryRevision() != file.observationRevision()
                    || !source.relativeRoute().equals(member.relativePath()) || !source.relativeRoute().equals(member.pathKey())
                    || !Objects.equals(member.observedSourceLocationRevision(), source.sourceRevision())
                    || !Objects.equals(member.observedLocationContextRevision(), receipt.contextRevision())
                    || !Objects.equals(member.lastPositiveScanRunSourceId(), source.scanRunSourceId())
                    || !Objects.equals(member.lastPositiveTraversalGeneration(), source.generation())) throw integrity();
        }
        return receipt;
    }
    public void requireContent(FileEntry file) {
        if (file.currentContentId() == null || catalog.findContentRecordById(file.currentContentId()).orElseThrow().sizeBytes() != file.sizeBytes()) throw integrity();
    }
    private static IllegalStateException integrity() { return new IllegalStateException("exFAT receipt/content integrity contradiction"); }
}
