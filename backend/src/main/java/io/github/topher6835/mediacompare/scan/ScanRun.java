package io.github.topher6835.mediacompare.scan;

public record ScanRun(
        Long id,
        String requestType,
        String status,
        Long workingSetId,
        long optionsVersion,
        String optionsJson,
        long createdAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String errorMessage) {
}
