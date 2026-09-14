package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.scan.ScanRunSource;

public record ScanRunSourceResponse(
        long sourceId,
        String status,
        long sourceLocationRevision,
        long traversalGeneration,
        Long completedGeneration) {

    public static ScanRunSourceResponse from(ScanRunSource source) {
        return new ScanRunSourceResponse(
                source.sourceId(),
                source.status(),
                source.sourceLocationRevision(),
                source.traversalGeneration(),
                source.completedGeneration());
    }
}
