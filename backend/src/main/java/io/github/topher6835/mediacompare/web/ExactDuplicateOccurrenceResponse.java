package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.catalog.FileCategory;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.matching.ExactDuplicateOccurrence;

public record ExactDuplicateOccurrenceResponse(
        long fileEntryId,
        long contentRecordId,
        long membershipId,
        long sourceId,
        String sourceName,
        String relativePath,
        String presenceStatus,
        String applicabilityStatus,
        String extension,
        FileCategory fileCategory,
        boolean matchesFilter) {

    public static ExactDuplicateOccurrenceResponse from(ExactDuplicateOccurrence occurrence) {
        return new ExactDuplicateOccurrenceResponse(
                occurrence.fileEntryId(),
                occurrence.contentRecordId(),
                occurrence.membershipId(),
                occurrence.sourceId(),
                occurrence.sourceName(),
                occurrence.relativePath(),
                occurrence.presenceStatus(),
                occurrence.applicabilityStatus(),
                occurrence.extensionKey() == null
                        ? null
                        : FileExtensionNormalizer.toApiValue(occurrence.extensionKey()),
                occurrence.fileCategory(),
                occurrence.matchesFilter());
    }
}
