package io.github.topher6835.mediacompare.library;

import java.util.List;

public record MediaLibraryPage(List<MediaLibraryItem> items, Long nextCursor) {
    public MediaLibraryPage {
        items = List.copyOf(items);
    }
}
