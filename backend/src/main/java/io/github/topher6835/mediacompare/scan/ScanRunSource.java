package io.github.topher6835.mediacompare.scan;

public record ScanRunSource(
        Long id,
        long scanRunId,
        long sourceId,
        String status,
        long sourceLocationRevision,
        long traversalGeneration,
        Long completedGeneration,
        Long startedAtMs,
        Long completedAtMs,
        String errorMessage) {
}
