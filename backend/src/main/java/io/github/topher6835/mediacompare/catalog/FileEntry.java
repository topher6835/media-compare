package io.github.topher6835.mediacompare.catalog;

/** One physical occurrence; Source-relative relationships live in SourceMembership. */
public record FileEntry(
        Long id,
        String locationIdentityStatus,
        String locationContextId,
        String locationPath,
        String locationKey,
        Long currentContentId,
        long sizeBytes,
        Long modifiedTimeEpochSecond,
        Integer modifiedTimeNano,
        String extensionKey,
        long observationRevision,
        long firstSeenAtMs,
        long lastSeenAtMs) {
}
