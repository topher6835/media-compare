package io.github.topher6835.mediacompare.matching;

public record ExactDuplicateOccurrence(
        long fileEntryId,
        long contentRecordId,
        long sourceId,
        String sourceName,
        String relativePath,
        String presenceStatus) {
}
