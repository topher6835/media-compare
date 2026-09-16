package io.github.topher6835.mediacompare.web;

import java.util.List;

import io.github.topher6835.mediacompare.matching.ExactDuplicateGroupPage;

public record ExactDuplicateGroupPageResponse(
        List<ExactDuplicateGroupSummaryResponse> groups,
        String nextAfterDigestHex) {

    public static ExactDuplicateGroupPageResponse from(ExactDuplicateGroupPage page) {
        return new ExactDuplicateGroupPageResponse(
                page.groups().stream().map(ExactDuplicateGroupSummaryResponse::from).toList(),
                page.nextAfterDigestHex());
    }
}
