package io.github.topher6835.mediacompare.web;

import java.util.HashSet;
import java.util.List;

public record ThumbnailScheduleRequest(List<Long> fileEntryIds) {
    public ThumbnailScheduleRequest {
        if (fileEntryIds == null || fileEntryIds.isEmpty() || fileEntryIds.size() > 100) {
            throw new IllegalArgumentException("fileEntryIds must contain 1..100 IDs");
        }
        var distinct = new HashSet<Long>();
        for (Long id : fileEntryIds) {
            if (id == null || id <= 0 || !distinct.add(id)) {
                throw new IllegalArgumentException("fileEntryIds must be positive and distinct");
            }
        }
        fileEntryIds = List.copyOf(fileEntryIds);
    }
}
