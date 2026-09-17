package io.github.topher6835.mediacompare.analysis;

public record AnalysisRecord(
        Long id,
        long contentRecordId,
        String analysisType,
        String analyzerId,
        String analyzerVersion,
        long configurationVersion,
        String configurationHash,
        String configurationJson,
        String resultJson,
        String status,
        long attemptCount,
        long createdAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String errorMessage) {
}
