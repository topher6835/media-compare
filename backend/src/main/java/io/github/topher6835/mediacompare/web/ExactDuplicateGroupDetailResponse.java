package io.github.topher6835.mediacompare.web;

import java.util.List;

import io.github.topher6835.mediacompare.matching.ExactDuplicateGroupDetails;
import io.github.topher6835.mediacompare.matching.ExactDuplicateGroupSummary;

public record ExactDuplicateGroupDetailResponse(
        String digestHex,
        long sizeBytes,
        long contentRecordCount,
        long redundantContentRecordCount,
        long presentOccurrenceCount,
        long missingOccurrenceCount,
        long sourceCount,
        long potentialStorageSavingsBytes,
        List<ExactDuplicateMemberResponse> members,
        List<ExactDuplicateOccurrenceResponse> occurrences) {

    public static ExactDuplicateGroupDetailResponse from(ExactDuplicateGroupDetails details) {
        ExactDuplicateGroupSummary summary = details.summary();
        return new ExactDuplicateGroupDetailResponse(
                summary.digestHex(),
                summary.sizeBytes(),
                summary.contentRecordCount(),
                summary.redundantContentRecordCount(),
                summary.presentOccurrenceCount(),
                summary.missingOccurrenceCount(),
                summary.sourceCount(),
                summary.potentialStorageSavingsBytes(),
                details.members().stream().map(ExactDuplicateMemberResponse::from).toList(),
                details.occurrences().stream().map(ExactDuplicateOccurrenceResponse::from).toList());
    }
}
