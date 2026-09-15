package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.scan.ContentAssignmentResult;

public record ContentAssignmentResponse(long scanRunId, long assignedCount, long skippedCount) {

    public static ContentAssignmentResponse from(ContentAssignmentResult result) {
        return new ContentAssignmentResponse(
                result.scanRunId(), result.assignedCount(), result.skippedCount());
    }
}
