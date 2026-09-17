package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;

public record MediaMetadataAnalysisDefinition(
        String analyzerId,
        String analyzerVersion,
        long configurationVersion,
        String configurationHash,
        String configurationJson) {

    public static final String ANALYSIS_TYPE = "MEDIA_METADATA";

    public MediaMetadataAnalysisDefinition {
        analyzerId = requireNonBlank(analyzerId, "analyzerId");
        analyzerVersion = requireNonBlank(analyzerVersion, "analyzerVersion");
        if (configurationVersion <= 0) {
            throw new IllegalArgumentException("configurationVersion must be positive");
        }
        configurationHash = requireNonBlank(configurationHash, "configurationHash");
        configurationJson = Objects.requireNonNull(configurationJson, "configurationJson");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
