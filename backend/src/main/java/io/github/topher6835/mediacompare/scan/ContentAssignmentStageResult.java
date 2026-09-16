package io.github.topher6835.mediacompare.scan;

public record ContentAssignmentStageResult(
        int version,
        long assignedCount,
        long skippedCount) {

    public static final int CURRENT_VERSION = 1;

    public ContentAssignmentStageResult {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported assignment result version: " + version);
        }
        if (assignedCount < 0 || skippedCount < 0) {
            throw new IllegalArgumentException("Assignment result counts cannot be negative");
        }
    }

    public static ContentAssignmentStageResult from(ContentAssignmentResult result) {
        return new ContentAssignmentStageResult(
                CURRENT_VERSION, result.assignedCount(), result.skippedCount());
    }
}
