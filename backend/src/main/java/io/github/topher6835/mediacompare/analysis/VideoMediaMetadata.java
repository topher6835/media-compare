package io.github.topher6835.mediacompare.analysis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record VideoMediaMetadata(
        List<String> containerFormats,
        Long durationMicros,
        String videoCodec,
        int width,
        int height,
        long videoStreamCount,
        String audioCodec,
        long audioStreamCount) {

    public VideoMediaMetadata {
        Objects.requireNonNull(containerFormats, "Container formats are required");
        if (containerFormats.isEmpty()) {
            throw new IllegalArgumentException("At least one container format is required");
        }
        var normalizedFormats = new LinkedHashSet<String>();
        for (String format : containerFormats) {
            normalizedFormats.add(ImageMediaMetadata.requireNormalizedName(format, "Container format"));
        }
        if (normalizedFormats.size() != containerFormats.size()) {
            throw new IllegalArgumentException("Container formats must not contain duplicates");
        }
        containerFormats = List.copyOf(containerFormats);
        if (durationMicros != null && durationMicros < 0) {
            throw new IllegalArgumentException("Video duration cannot be negative");
        }
        videoCodec = ImageMediaMetadata.requireNormalizedName(videoCodec, "Video codec");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }
        if (videoStreamCount <= 0) {
            throw new IllegalArgumentException("Video stream count must be positive");
        }
        if (audioStreamCount < 0) {
            throw new IllegalArgumentException("Audio stream count cannot be negative");
        }
        if (audioStreamCount == 0 && audioCodec != null) {
            throw new IllegalArgumentException("Audio codec requires at least one audio stream");
        }
        if (audioCodec != null) {
            audioCodec = ImageMediaMetadata.requireNormalizedName(audioCodec, "Audio codec");
        }
    }
}
