package io.github.topher6835.mediacompare.analysis;

public record ContentHashingResult(
        long scanRunId,
        long hashedCount,
        long cachedCount,
        long skippedCount,
        long failedCount) {
}
