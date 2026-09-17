package io.github.topher6835.mediacompare.analysis;

public record UnsupportedMediaMetadata(int version) implements MediaMetadataResult {

    public UnsupportedMediaMetadata {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported media metadata result version: " + version);
        }
    }

    @Override
    public MediaMetadataOutcome outcome() {
        return MediaMetadataOutcome.UNSUPPORTED;
    }
}
