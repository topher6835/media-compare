package io.github.topher6835.mediacompare.matching;

import io.github.topher6835.mediacompare.catalog.FileCategory;

public record ExactDuplicateFilterOption(
        String extensionKey,
        FileCategory fileCategory,
        long exactDuplicateGroupCount,
        long retainedOccurrenceCount) {
}
