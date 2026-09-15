package io.github.topher6835.mediacompare.catalog;

public record FileObservation(
        long sourceId,
        String relativePath,
        String pathKey,
        long sizeBytes,
        long modifiedTimeEpochSecond,
        int modifiedTimeNano,
        long observedAtMs,
        long scanRunSourceId,
        long traversalGeneration) {
}
