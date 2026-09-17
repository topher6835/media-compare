package io.github.topher6835.mediacompare.analysis;

public record MediaMetadataFileCandidate(
        long contentRecordId,
        long expectedContentSizeBytes,
        long fileEntryId,
        long sourceId,
        String sourceRootPath,
        long sourceLocationRevision,
        String relativePath,
        long observationRevision,
        long expectedSizeBytes,
        Long expectedModifiedTimeEpochSecond,
        Integer expectedModifiedTimeNano) {
}
