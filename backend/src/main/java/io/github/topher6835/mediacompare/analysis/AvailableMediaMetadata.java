package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;

public record AvailableMediaMetadata(
        int version,
        MediaKind mediaKind,
        ImageMediaMetadata image,
        VideoMediaMetadata video) implements MediaMetadataResult {

    public AvailableMediaMetadata {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported media metadata result version: " + version);
        }
        Objects.requireNonNull(mediaKind, "Media kind is required");
        if (mediaKind == MediaKind.IMAGE && (image == null || video != null)) {
            throw new IllegalArgumentException("IMAGE metadata must contain only an image payload");
        }
        if (mediaKind == MediaKind.VIDEO && (video == null || image != null)) {
            throw new IllegalArgumentException("VIDEO metadata must contain only a video payload");
        }
    }

    @Override
    public MediaMetadataOutcome outcome() {
        return MediaMetadataOutcome.AVAILABLE;
    }
}
