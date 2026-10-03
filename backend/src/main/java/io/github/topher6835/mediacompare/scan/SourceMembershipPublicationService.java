package io.github.topher6835.mediacompare.scan;

import java.util.Objects;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.CurrentLocationAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceMembership;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

/** Short, writer-reserved publication of trusted v3 positives and missing claims. */
@Service
public class SourceMembershipPublicationService {
    private final CatalogRepository catalog;
    private final LocationContextRepository contexts;
    private final SourceMembershipRepository memberships;
    private final ScanRepository scans;
    private final LocationPathCodec paths = new LocationPathCodec();
    private io.github.topher6835.mediacompare.catalog.ExfatOccurrenceRepository occurrences;
    private ExfatScanBundles bundles;
    @org.springframework.beans.factory.annotation.Autowired
    void exfatBundles(ExfatScanBundles bundles) { this.bundles = bundles; }

    @org.springframework.beans.factory.annotation.Autowired
    void exfatOccurrences(io.github.topher6835.mediacompare.catalog.ExfatOccurrenceRepository occurrences) { this.occurrences = occurrences; }

    /** Caller owns the exact window gate and writer; the final receipt already contains reserved IDs. */
    @Transactional
    public FileEntry publishExfat(long fileId, io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt receipt) {
        contexts.reserveWrite();
        for (var source : receipt.sources()) {
            var a = bundles.require(receipt.scanRunId(), source.sourceId());
            require(a.window().windowId().equals(source.windowUuid()) && a.bundleUuid().equals(receipt.bundleUuid())
                    && a.scanRunSourceId() == source.scanRunSourceId() && a.generation() == source.generation()
                    && a.jobId() == receipt.scanJobId(), "Receipt publication authority disagrees");
            bundles.requireCatalog(a, ScanExecutionDefinition.DISCOVERY, "DISCOVERING");
        }
        var filePath = paths.decode(receipt.locationPath());
        require(occurrences.findNativeAddressConflict(receipt.contextId(), receipt.locationKey()).isEmpty(),
                "Native resolved address contradicts exFAT occurrence profile");
        long after = 0;
        while (true) {
            var page = occurrences.activeRoutes(receipt.contextId(), after, 250);
            if (page.isEmpty()) break;
            for (var old : page) {
                after = old.id();
                if (WindowsExfatDiscoveryWalker.sameWindowsRoute(paths.decode(old.locationPath()), filePath)) {
                    require(old.occurrenceToken() != null, "Native resolved alias contradicts exFAT profile");
                    occurrences.retireOccurrence(old);
                }
            }
        }
        for (var source : receipt.sources()) {
            var collision = memberships.findActiveAtPath(source.sourceId(), source.relativeRoute()).orElse(null);
            if (collision != null) {
                var old = memberships.findById(collision.fileEntryId()).orElseThrow();
                require("UNRESOLVED".equals(old.locationIdentityStatus()), "Active route contradicts new occurrence");
                occurrences.retireChecked(collision);
            }
        }
        long now = receipt.observationFinishedAtMs();
        var file = new FileEntry(fileId, "RESOLVED", receipt.contextId(), receipt.locationPath(), receipt.locationKey(),
                null, receipt.sizeBytes(), receipt.modifiedTimeEpochSecond(), receipt.modifiedTimeNano(),
                FileExtensionNormalizer.fromRelativePath(receipt.sources().getFirst().relativeRoute()), 0, now, now,
                receipt.occurrenceToken(), new io.github.topher6835.mediacompare.filesystem.ExfatObservationReceiptCodec().encode(receipt));
        occurrences.insertOccurrence(file, io.github.topher6835.mediacompare.filesystem.FileSystemProfile.EXFAT,
                io.github.topher6835.mediacompare.filesystem.FileSystemProfile.EXFAT);
        for (var source : receipt.sources()) {
            occurrences.insertOccurrenceMembership(new SourceMembership(source.membershipId(), source.sourceId(), fileId,
                    source.relativeRoute(), source.relativeRoute(), "ACTIVE", "PRESENT", 0, 0, now, now,
                    source.scanRunSourceId(), source.generation(), source.sourceRevision(), receipt.contextRevision()));
        }
        return file;
    }

    public SourceMembershipPublicationService(CatalogRepository catalog,
            LocationContextRepository contexts, SourceMembershipRepository memberships,
            ScanRepository scans) {
        this.catalog = catalog;
        this.contexts = contexts;
        this.memberships = memberships;
        this.scans = scans;
    }

    @Transactional
    public SourceMembership publish(ResolvedFileCandidate candidate,
            long scanRunSourceId, long generation, long observedAtMs) {
        Objects.requireNonNull(candidate, "Resolved candidate");
        contexts.reserveWrite();
        requireCurrent(candidate.sourceId(), candidate.sourceLocationRevision(),
                candidate.locationContextId(), candidate.locationContextRevision());
        Source source = catalog.findSourceById(candidate.sourceId()).orElseThrow();
        LocationContext context = contexts.findById(candidate.locationContextId()).orElseThrow();
        var route = CurrentLocationAuthority.requireCurrentHost(source, context);
        require("apfs".equals(route.fileSystemType()) || "ntfs".equals(route.fileSystemType()),
                "Native publication does not accept exFAT occurrence history");
        var fileLocation = candidate.fileLocationPath();
        require(LocationKeyCodec.matches(fileLocation, candidate.fileLocationKey())
                && route.anchor().contains(fileLocation)
                && route.root().contains(fileLocation)
                && candidate.relativePath().equals(SourceRelativePath.from(
                        route.root(), fileLocation))
                && candidate.relativePathKey().equals(candidate.relativePath())
                && route.fileSystemType().equals(candidate.fileSystemType())
                && route.volumeId().equals(candidate.volumeUuid()),
                "Resolved candidate disagrees with current Source/context authority");
        ScanRunSource runSource = scans.findScanRunSourceById(scanRunSourceId).orElseThrow();
        require(runSource.sourceId() == candidate.sourceId()
                && runSource.sourceLocationRevision() == candidate.sourceLocationRevision()
                && "DISCOVERING".equals(runSource.status())
                && runSource.traversalGeneration() == generation,
                "ScanRunSource is not publishing this discovery generation");
        String path = paths.encode(candidate.fileLocationPath());
        String key = candidate.fileLocationKey().value();
        FileEntry fileEntry = memberships.findResolved(candidate.locationContextId(), key)
                .orElse(null);
        boolean inserted = false;
        if (fileEntry == null) {
            try {
                fileEntry = memberships.insertResolved(new FileEntry(null, "RESOLVED",
                        candidate.locationContextId(), path, key, null, candidate.sizeBytes(),
                        candidate.modifiedTimeEpochSecond(), candidate.modifiedTimeNano(),
                        FileExtensionNormalizer.fromRelativePath(candidate.relativePath()), 0,
                        observedAtMs, observedAtMs));
                inserted = true;
            } catch (DataIntegrityViolationException collision) {
                fileEntry = memberships.findResolved(candidate.locationContextId(), key)
                        .orElseThrow(() -> collision);
            }
        }
        if (!inserted) {
            requireValidResolved(fileEntry);
            require(path.equals(fileEntry.locationPath()),
                    "Resolved location key identifies a different structured path");
            boolean changed = fileEntry.sizeBytes() != candidate.sizeBytes()
                    || !Objects.equals(fileEntry.modifiedTimeEpochSecond(),
                            candidate.modifiedTimeEpochSecond())
                    || !Objects.equals(fileEntry.modifiedTimeNano(), candidate.modifiedTimeNano());
            require(memberships.refreshResolved(fileEntry, candidate.sizeBytes(),
                    candidate.modifiedTimeEpochSecond(), candidate.modifiedTimeNano(),
                    Math.max(observedAtMs, fileEntry.lastSeenAtMs()), changed) == 1,
                    "FileEntry changed during resolved publication");
            fileEntry = memberships.findById(fileEntry.id()).orElseThrow();
        }

        SourceMembership occupyingPath = memberships.findActiveAtPath(
                candidate.sourceId(), candidate.relativePathKey()).orElse(null);
        if (occupyingPath != null && occupyingPath.fileEntryId() != fileEntry.id()) {
            FileEntry previous = memberships.findById(occupyingPath.fileEntryId()).orElseThrow();
            require("UNRESOLVED".equals(previous.locationIdentityStatus()),
                    "Active path is held by a different resolved FileEntry");
            require(memberships.retire(occupyingPath) == 1,
                    "Legacy membership changed before retirement");
        }

        SourceMembership prior = memberships.findBySourceAndFile(candidate.sourceId(), fileEntry.id())
                .orElse(null);
        if (prior == null) {
            return memberships.insert(new SourceMembership(null, candidate.sourceId(), fileEntry.id(),
                    candidate.relativePath(), candidate.relativePathKey(), "ACTIVE", "PRESENT", 0,
                    fileEntry.observationRevision(), observedAtMs, observedAtMs,
                    scanRunSourceId, generation, candidate.sourceLocationRevision(),
                    candidate.locationContextRevision()));
        }
        SourceMembership next = new SourceMembership(prior.id(), prior.sourceId(), prior.fileEntryId(),
                candidate.relativePath(), candidate.relativePathKey(), "ACTIVE", "PRESENT",
                prior.membershipRevision(), fileEntry.observationRevision(), prior.firstSeenAtMs(),
                Math.max(observedAtMs, prior.lastSeenAtMs()), scanRunSourceId, generation,
                candidate.sourceLocationRevision(), candidate.locationContextRevision());
        if (samePersistedValues(prior, next)) {
            return prior;
        }
        require(memberships.refreshMembership(prior, next) == 1,
                "SourceMembership changed during positive publication");
        return memberships.findMembershipById(prior.id()).orElseThrow();
    }

    @Transactional
    public int reconcile(MissingClaimAuthority claim, ScanRunSource runSource, long completedAtMs) {
        Objects.requireNonNull(claim, "Missing-claim authority");
        contexts.reserveWrite();
        if (claim.exfat() != null) {
            var a = claim.exfat();
            require(a.missingClaim().equals(claim) && a.scanRunSourceId() == runSource.id()
                    && a.generation() == runSource.traversalGeneration(), "Foreign exFAT missing claim");
            bundles.requireCatalog(a, ScanExecutionDefinition.RECONCILIATION, "DISCOVERED");
            int changed = occurrences.markUnseenMissing(claim.sourceId(), claim.locationContextId(), a.scanRunSourceId(), a.generation());
            require(scans.completeSourceReconciliation(a.scanRunSourceId(), a.generation(), completedAtMs) == 1,
                    "exFAT reconciliation generation changed");
            return changed;
        }
        requireCurrent(claim.sourceId(), claim.sourceLocationRevision(), claim.locationContextId(),
                claim.locationContextRevision());
        Source source = catalog.findSourceById(claim.sourceId()).orElseThrow();
        require(CurrentLocationAuthority.requireCurrentHost(source, contexts.findById(claim.locationContextId())
                .orElseThrow()).root().equals(claim.scope()),
                "Missing claim scope disagrees with current Source root");
        ScanRunSource current = scans.findScanRunSourceById(runSource.id()).orElseThrow();
        require(current.sourceId() == claim.sourceId()
                && current.sourceLocationRevision() == claim.sourceLocationRevision()
                && "DISCOVERED".equals(current.status())
                && current.traversalGeneration() == runSource.traversalGeneration()
                && current.completedGeneration() == null,
                "ScanRunSource is not eligible for trusted reconciliation");
        int changed = memberships.markUnseenMissing(
                claim.sourceId(), current.id(), current.traversalGeneration());
        require(scans.completeSourceReconciliation(current.id(), current.traversalGeneration(),
                completedAtMs) == 1, "ScanRunSource changed during reconciliation");
        return changed;
    }

    public void requireCurrent(long sourceId, long sourceRevision, String contextId, long contextRevision) {
        Source source = catalog.findSourceById(sourceId).orElseThrow();
        LocationContext context = contexts.findById(contextId).orElseThrow();
        require(source.locationRevision() == sourceRevision
                && context.revision() == contextRevision
                && contextId.equals(source.boundLocationContextId()),
                "Source or LocationContext authority changed");
        CurrentLocationAuthority.requireCurrentHost(source, context);
    }

    private static void requireValidResolved(FileEntry entry) {
        require("RESOLVED".equals(entry.locationIdentityStatus())
                && entry.locationContextId() != null && entry.locationPath() != null
                && entry.locationKey() != null, "Persisted resolved FileEntry is incomplete");
        var path = new LocationPathCodec().decode(entry.locationPath());
        require(LocationKeyCodec.matches(path, LocationKey.parse(entry.locationKey())),
                "Persisted resolved FileEntry path and key disagree");
    }

    private static boolean samePersistedValues(SourceMembership a, SourceMembership b) {
        return a.relativePath().equals(b.relativePath()) && a.pathKey().equals(b.pathKey())
                && a.applicabilityStatus().equals(b.applicabilityStatus())
                && a.presenceStatus().equals(b.presenceStatus())
                && a.observedFileEntryRevision() == b.observedFileEntryRevision()
                && a.lastSeenAtMs() == b.lastSeenAtMs()
                && Objects.equals(a.lastPositiveScanRunSourceId(), b.lastPositiveScanRunSourceId())
                && Objects.equals(a.lastPositiveTraversalGeneration(), b.lastPositiveTraversalGeneration())
                && Objects.equals(a.observedSourceLocationRevision(), b.observedSourceLocationRevision())
                && Objects.equals(a.observedLocationContextRevision(), b.observedLocationContextRevision());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
