package io.github.topher6835.mediacompare.web;

import java.util.List;

import io.github.topher6835.mediacompare.matching.ExactDuplicateFilterOption;

public record ExactDuplicateFilterOptionsResponse(
        List<ExactDuplicateFilterOptionResponse> extensions) {

    public static ExactDuplicateFilterOptionsResponse from(List<ExactDuplicateFilterOption> options) {
        return new ExactDuplicateFilterOptionsResponse(
                options.stream().map(ExactDuplicateFilterOptionResponse::from).toList());
    }
}
