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
        long lastSeenAtMs,
        String occurrenceToken,
        String observationEvidenceJson) {

    /** Native and migrated entries have no exFAT observation evidence. */
    public FileEntry(Long id, String locationIdentityStatus, String locationContextId,
            String locationPath, String locationKey, Long currentContentId, long sizeBytes,
            Long modifiedTimeEpochSecond, Integer modifiedTimeNano, String extensionKey,
            long observationRevision, long firstSeenAtMs, long lastSeenAtMs) {
        this(id, locationIdentityStatus, locationContextId, locationPath, locationKey,
                currentContentId, sizeBytes, modifiedTimeEpochSecond, modifiedTimeNano,
                extensionKey, observationRevision, firstSeenAtMs, lastSeenAtMs, null, null);
    }
}
