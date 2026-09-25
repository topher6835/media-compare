package io.github.topher6835.mediacompare.analysis;

public record MediaMetadataFileCandidate(
        long contentRecordId,
        long expectedContentSizeBytes,
        long fileEntryId,
        long membershipId,
        long sourceId,
        long sourceLocationRevision,
        String contextId,
        long contextRevision,
        long membershipRevision,
        String locationPath,
        String locationKey,
        long observationRevision,
        long expectedSizeBytes,
        Long expectedModifiedTimeEpochSecond,
        Integer expectedModifiedTimeNano) {
}
