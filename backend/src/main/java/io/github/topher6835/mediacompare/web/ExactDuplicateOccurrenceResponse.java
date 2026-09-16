package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.matching.ExactDuplicateOccurrence;

public record ExactDuplicateOccurrenceResponse(
        long fileEntryId,
        long contentRecordId,
        long sourceId,
        String sourceName,
        String relativePath,
        String presenceStatus) {

    public static ExactDuplicateOccurrenceResponse from(ExactDuplicateOccurrence occurrence) {
        return new ExactDuplicateOccurrenceResponse(
                occurrence.fileEntryId(),
                occurrence.contentRecordId(),
                occurrence.sourceId(),
                occurrence.sourceName(),
                occurrence.relativePath(),
                occurrence.presenceStatus());
    }
}
