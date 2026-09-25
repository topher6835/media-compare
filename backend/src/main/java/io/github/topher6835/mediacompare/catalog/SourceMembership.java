package io.github.topher6835.mediacompare.catalog;

/** One retained Source-to-FileEntry relationship and its presence authority. */
public record SourceMembership(
        Long id,
        long sourceId,
        long fileEntryId,
        String relativePath,
        String pathKey,
        String applicabilityStatus,
        String presenceStatus,
        long membershipRevision,
        long observedFileEntryRevision,
        long firstSeenAtMs,
        long lastSeenAtMs,
        Long lastPositiveScanRunSourceId,
        Long lastPositiveTraversalGeneration,
        Long observedSourceLocationRevision,
        Long observedLocationContextRevision) {
}
