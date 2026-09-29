package io.github.topher6835.mediacompare.library;

import java.util.List;

public record MediaLibraryGroupPage(List<MediaLibraryGroupSummary> groups, Long nextCursor) {
    public MediaLibraryGroupPage {
        groups = List.copyOf(groups);
    }
}
