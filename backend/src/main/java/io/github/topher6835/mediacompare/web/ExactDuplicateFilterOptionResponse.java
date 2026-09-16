package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.catalog.FileCategory;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.matching.ExactDuplicateFilterOption;

public record ExactDuplicateFilterOptionResponse(
        String extension,
        FileCategory fileCategory,
        long exactDuplicateGroupCount,
        long retainedOccurrenceCount) {

    public static ExactDuplicateFilterOptionResponse from(ExactDuplicateFilterOption option) {
        return new ExactDuplicateFilterOptionResponse(
                FileExtensionNormalizer.toApiValue(option.extensionKey()),
                option.fileCategory(),
                option.exactDuplicateGroupCount(),
                option.retainedOccurrenceCount());
    }
}
