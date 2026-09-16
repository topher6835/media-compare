package io.github.topher6835.mediacompare.matching;

import java.util.List;

public record ExactDuplicateFilterMatch(
        long matchingOccurrenceCount,
        List<String> matchingExtensionKeys) {
}
