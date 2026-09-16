package io.github.topher6835.mediacompare.analysis;

public record ContentHashingStageResult(
        int version,
        long hashedCount,
        long cachedCount,
        long skippedCount,
        long failedCount) {

    public static final int CURRENT_VERSION = 1;

    public ContentHashingStageResult {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported hashing result version: " + version);
        }
        if (hashedCount < 0 || cachedCount < 0 || skippedCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("Hashing result counts cannot be negative");
        }
    }

    public static ContentHashingStageResult from(ContentHashingResult result) {
        return new ContentHashingStageResult(
                CURRENT_VERSION, result.hashedCount(), result.cachedCount(),
                result.skippedCount(), result.failedCount());
    }
}
