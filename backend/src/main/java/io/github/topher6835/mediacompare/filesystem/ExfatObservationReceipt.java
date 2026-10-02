package io.github.topher6835.mediacompare.filesystem;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.scan.IndexingRunService;

/** Immutable observation history. Window UUIDs here never grant live authority. */
public record ExfatObservationReceipt(
        String version, String occurrenceToken, long fileObservationRevision,
        String locationPath, String locationKey, String contextId, long contextRevision,
        long sizeBytes, long byteCount, String sha256,
        long modifiedTimeEpochSecond, int modifiedTimeNano,
        ClassificationEvidence before, ClassificationEvidence after,
        long observationStartedAtMs, long observationFinishedAtMs,
        String bundleUuid, long scanRunId, long scanJobId, List<SourceEntry> sources) {

    public static final String VERSION = "windows-exfat-observation-v1";

    public ExfatObservationReceipt {
        ExfatReceiptValues.require(VERSION.equals(version), "Unsupported observation receipt version");
        ExfatReceiptValues.uuid(occurrenceToken);
        ExfatReceiptValues.uuid(contextId);
        ExfatReceiptValues.uuid(bundleUuid);
        ExfatReceiptValues.nonnegative(fileObservationRevision, contextRevision, sizeBytes,
                byteCount, observationStartedAtMs, observationFinishedAtMs);
        ExfatReceiptValues.positive(scanRunId, scanJobId);
        ExfatReceiptValues.require(sizeBytes == byteCount, "Incomplete observation byte count");
        ExfatReceiptValues.require(sha256 != null && sha256.matches("[0-9a-f]{64}"), "Invalid SHA-256");
        Instant modified = ExfatReceiptValues.instant(modifiedTimeEpochSecond, modifiedTimeNano);
        LocationPath file = ExfatReceiptValues.path(locationPath, locationKey);
        ExfatReceiptValues.require(!file.components().isEmpty(), "Observation must name a file");
        ExfatReceiptValues.require(observationFinishedAtMs >= observationStartedAtMs,
                "Observation finish precedes start");
        Objects.requireNonNull(before, "before").requireMatches(file, sizeBytes, modified);
        Objects.requireNonNull(after, "after").requireMatches(file, sizeBytes, modified);
        ExfatReceiptValues.require(before.volumeSerial().equals(after.volumeSerial())
                && before.volumeGuid().equals(after.volumeGuid())
                && Objects.equals(before.contradictionOnlyLegacyIndex(), after.contradictionOnlyLegacyIndex()),
                "Before/after volume or contradiction evidence disagrees");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        ExfatReceiptValues.require(!sources.isEmpty() && sources.size() <= IndexingRunService.MAX_SOURCE_IDS,
                "Receipt Source count outside indexing limit");
        long previousSourceId = 0;
        var memberships = new HashSet<Long>();
        var runSources = new HashSet<Long>();
        var windows = new HashSet<String>();
        for (SourceEntry source : sources) {
            ExfatReceiptValues.require(source.sourceId() > previousSourceId,
                    "Receipt Sources must be unique and ordered by Source ID");
            ExfatReceiptValues.require(memberships.add(source.membershipId())
                    && runSources.add(source.scanRunSourceId()) && windows.add(source.windowUuid()),
                    "Repeated membership, traversal or window in receipt");
            LocationPath root = ExfatReceiptValues.path(source.rootPath(), source.rootPathKey());
            ExfatReceiptValues.require(root.contains(file) && !root.equals(file), "Source does not contain file");
            String relative = String.join("/", file.components().subList(root.components().size(),
                    file.components().size()));
            ExfatReceiptValues.require(relative.equals(source.relativeRoute()), "Relative route disagrees");
            previousSourceId = source.sourceId();
        }
    }

    public record SourceEntry(long sourceId, long sourceRevision, String rootPath, String rootPathKey,
            String windowUuid, long membershipId, long membershipRevision, long scanRunSourceId,
            long generation, String relativeRoute) {
        public SourceEntry {
            ExfatReceiptValues.positive(sourceId, membershipId, scanRunSourceId, generation);
            ExfatReceiptValues.nonnegative(sourceRevision, membershipRevision);
            ExfatReceiptValues.uuid(windowUuid);
            ExfatReceiptValues.path(rootPath, rootPathKey);
            ExfatReceiptValues.text(relativeRoute, 16 * 1024);
        }
    }

    /** Typed, bounded native/NIO facts, without handles, pointers or physical identity claims. */
    public record ClassificationEvidence(long nativeAttributes, long nativeSizeBytes,
            long nativeModifiedTime100ns, boolean nioRegularFile, boolean nioDirectory,
            boolean nioSymbolicLink, boolean nioOther, long nioSizeBytes,
            long nioModifiedTimeEpochSecond, int nioModifiedTimeNano, String nioFileSystemType,
            String finalRoute, String volumeSerial, String volumeGuid, String contradictionOnlyLegacyIndex) {
        public ClassificationEvidence {
            ExfatReceiptValues.nonnegative(nativeAttributes, nativeSizeBytes, nativeModifiedTime100ns, nioSizeBytes);
            ExfatReceiptValues.require(nativeAttributes <= 0xffffffffL && (nativeAttributes & 0x410) == 0
                    && nioRegularFile && !nioDirectory && !nioSymbolicLink && !nioOther,
                    "Observation classification is not a regular no-link file");
            ExfatReceiptValues.instant(nioModifiedTimeEpochSecond, nioModifiedTimeNano);
            ExfatReceiptValues.require("exFAT".equals(nioFileSystemType), "Expected exFAT NIO evidence");
            ExfatReceiptValues.finalRoute(finalRoute);
            ExfatReceiptValues.require(volumeSerial != null && volumeSerial.matches("[0-9a-f]{8}"),
                    "Invalid exFAT volume serial");
            ExfatReceiptValues.volumeGuid(volumeGuid);
            ExfatReceiptValues.require(contradictionOnlyLegacyIndex == null
                    || contradictionOnlyLegacyIndex.matches("[0-9a-f]{16}"), "Invalid legacy contradiction evidence");
        }

        private void requireMatches(LocationPath file, long size, Instant modified) {
            long ticks = Math.subtractExact(nativeModifiedTime100ns, 116444736000000000L);
            Instant nativeModified = Instant.ofEpochSecond(Math.floorDiv(ticks, 10_000_000),
                    Math.floorMod(ticks, 10_000_000) * 100);
            ExfatReceiptValues.require(nativeSizeBytes == size && nioSizeBytes == size
                    && modified.equals(nativeModified)
                    && modified.equals(Instant.ofEpochSecond(nioModifiedTimeEpochSecond, nioModifiedTimeNano))
                    && file.equals(ExfatReceiptValues.finalRoute(finalRoute)),
                    "Classification disagrees with captured path/size/mtime");
        }
    }
}
