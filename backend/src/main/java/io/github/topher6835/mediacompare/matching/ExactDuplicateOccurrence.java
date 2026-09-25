package io.github.topher6835.mediacompare.matching;

import io.github.topher6835.mediacompare.catalog.FileCategory;

public record ExactDuplicateOccurrence(
        long fileEntryId,
        long contentRecordId,
        long membershipId,
        long sourceId,
        String sourceName,
        String relativePath,
        String presenceStatus,
        String applicabilityStatus,
        String extensionKey,
        FileCategory fileCategory,
        boolean matchesFilter) {
}
