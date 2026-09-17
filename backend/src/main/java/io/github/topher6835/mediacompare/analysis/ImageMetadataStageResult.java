package io.github.topher6835.mediacompare.analysis;

public record ImageMetadataStageResult(
        int version,
        long candidatesAttempted,
        long completedAvailable,
        long completedUnsupported,
        long failed,
        long staleOrUnavailable) {

    public static final int CURRENT_VERSION = 1;

    public ImageMetadataStageResult {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported image metadata stage result version: " + version);
        }
        if (candidatesAttempted < 0 || completedAvailable < 0 || completedUnsupported < 0
                || failed < 0 || staleOrUnavailable < 0) {
            throw new IllegalArgumentException("Image metadata stage result counts cannot be negative");
        }
        if (candidatesAttempted
                != completedAvailable + completedUnsupported + failed + staleOrUnavailable) {
            throw new IllegalArgumentException("Image metadata stage result counts must match candidatesAttempted");
        }
    }
}
