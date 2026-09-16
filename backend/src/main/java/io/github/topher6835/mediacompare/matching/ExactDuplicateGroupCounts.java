package io.github.topher6835.mediacompare.matching;

record ExactDuplicateGroupCounts(
        String digestHex,
        long sizeBytes,
        long contentRecordCount,
        long presentOccurrenceCount,
        long missingOccurrenceCount,
        long sourceCount) {
}
