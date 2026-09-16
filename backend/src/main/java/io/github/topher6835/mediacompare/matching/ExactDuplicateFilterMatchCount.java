package io.github.topher6835.mediacompare.matching;

record ExactDuplicateFilterMatchCount(
        String digestHex,
        String extensionKey,
        long occurrenceCount) {
}
