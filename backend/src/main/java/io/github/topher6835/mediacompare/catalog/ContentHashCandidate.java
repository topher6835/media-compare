package io.github.topher6835.mediacompare.catalog;

public record ContentHashCandidate(
        long fileEntryId,
        long contentRecordId,
        long sourceId,
        String relativePath,
        long observationRevision,
        long sizeBytes,
        Long modifiedTimeEpochSecond,
        Integer modifiedTimeNano,
        long sourceLocationRevision) {
}
