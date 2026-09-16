package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.analysis.ContentHashingResult;

public record ContentHashingResponse(
        long scanRunId,
        long hashedCount,
        long cachedCount,
        long skippedCount,
        long failedCount) {

    public static ContentHashingResponse from(ContentHashingResult result) {
        return new ContentHashingResponse(
                result.scanRunId(), result.hashedCount(), result.cachedCount(),
                result.skippedCount(), result.failedCount());
    }
}
