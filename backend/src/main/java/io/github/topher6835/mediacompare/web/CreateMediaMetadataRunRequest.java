package io.github.topher6835.mediacompare.web;

import java.util.List;
import java.util.HashSet;

public record CreateMediaMetadataRunRequest(Long indexingScanRunId, List<SourceAuthorityWindowRequest> authorityWindows) {
    public CreateMediaMetadataRunRequest {
        if ((indexingScanRunId == null) == (authorityWindows == null)
                || indexingScanRunId != null && indexingScanRunId <= 0) throw new IllegalArgumentException("Select one metadata ownership mode");
        if (authorityWindows != null) authorityWindows = checkedWindows(authorityWindows);
    }
    public static List<SourceAuthorityWindowRequest> checkedWindows(List<SourceAuthorityWindowRequest> windows) {
        if (windows.isEmpty() || windows.size() > 64) throw new IllegalArgumentException("Expected 1..64 authority windows");
        var seen = new HashSet<Long>();
        for (var window : windows) if (window == null || !seen.add(window.sourceId())) throw new IllegalArgumentException("Duplicate authority Source");
        return List.copyOf(windows);
    }
}
