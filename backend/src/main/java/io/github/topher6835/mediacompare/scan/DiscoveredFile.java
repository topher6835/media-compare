package io.github.topher6835.mediacompare.scan;

public record DiscoveredFile(
        String relativePath,
        long sizeBytes,
        long modifiedTimeEpochSecond,
        int modifiedTimeNano) {
}
