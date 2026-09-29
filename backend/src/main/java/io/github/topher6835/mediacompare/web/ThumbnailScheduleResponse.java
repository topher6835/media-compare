package io.github.topher6835.mediacompare.web;

import java.util.List;
import io.github.topher6835.mediacompare.preview.ThumbnailScheduler;

public record ThumbnailScheduleResponse(List<ThumbnailScheduler.Result> results) {
    public ThumbnailScheduleResponse {
        results = List.copyOf(results);
    }
}
