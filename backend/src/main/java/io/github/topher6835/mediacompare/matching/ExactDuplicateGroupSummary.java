package io.github.topher6835.mediacompare.matching;

public record ExactDuplicateGroupSummary(
        String digestHex,
        long sizeBytes,
        long contentRecordCount,
        long redundantContentRecordCount,
        long presentOccurrenceCount,
        long missingOccurrenceCount,
        long sourceCount,
        long potentialStorageSavingsBytes,
        ExactDuplicateFilterMatch filterMatch) {
}
