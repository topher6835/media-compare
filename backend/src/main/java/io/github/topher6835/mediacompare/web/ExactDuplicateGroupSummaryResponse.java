package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.matching.ExactDuplicateGroupSummary;

public record ExactDuplicateGroupSummaryResponse(
        String digestHex,
        long sizeBytes,
        long contentRecordCount,
        long redundantContentRecordCount,
        long presentOccurrenceCount,
        long missingOccurrenceCount,
        long sourceCount,
        long potentialStorageSavingsBytes) {

    public static ExactDuplicateGroupSummaryResponse from(ExactDuplicateGroupSummary summary) {
        return new ExactDuplicateGroupSummaryResponse(
                summary.digestHex(),
                summary.sizeBytes(),
                summary.contentRecordCount(),
                summary.redundantContentRecordCount(),
                summary.presentOccurrenceCount(),
                summary.missingOccurrenceCount(),
                summary.sourceCount(),
                summary.potentialStorageSavingsBytes());
    }
}
