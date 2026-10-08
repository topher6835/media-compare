package io.github.topher6835.mediacompare.web;

import java.util.List;

public record CreateIndexingRunRequest(String requestKey, List<Long> sourceIds,
        List<SourceAuthorityWindowRequest> authorityWindows) {
    public CreateIndexingRunRequest(String requestKey, List<Long> sourceIds) { this(requestKey, sourceIds, null); }
    public CreateIndexingRunRequest {
        if (sourceIds != null) sourceIds = List.copyOf(sourceIds);
        if (authorityWindows != null && !authorityWindows.isEmpty())
            authorityWindows = CreateMediaMetadataRunRequest.checkedWindows(authorityWindows);
        else authorityWindows = List.of();
    }
}
