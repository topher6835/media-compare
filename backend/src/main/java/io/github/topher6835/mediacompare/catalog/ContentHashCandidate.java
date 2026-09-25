package io.github.topher6835.mediacompare.catalog;

public record ContentHashCandidate(
        long fileEntryId,
        long contentRecordId,
        long membershipId,
        long sourceId,
        String contextId,
        long contextRevision,
        long membershipRevision,
        String locationPath,
        String locationKey,
        long observationRevision,
        long sizeBytes,
        Long modifiedTimeEpochSecond,
        Integer modifiedTimeNano,
        long sourceLocationRevision) {
}
