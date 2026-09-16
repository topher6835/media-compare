package io.github.topher6835.mediacompare.web;

import java.util.List;

import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.matching.ExactDuplicateFilterMatch;

public record ExactDuplicateFilterMatchResponse(
        long matchingOccurrenceCount,
        List<String> matchingExtensions) {

    public static ExactDuplicateFilterMatchResponse from(ExactDuplicateFilterMatch match) {
        if (match == null) {
            return null;
        }
        return new ExactDuplicateFilterMatchResponse(
                match.matchingOccurrenceCount(),
                match.matchingExtensionKeys().stream()
                        .map(FileExtensionNormalizer::toApiValue)
                        .toList());
    }
}
