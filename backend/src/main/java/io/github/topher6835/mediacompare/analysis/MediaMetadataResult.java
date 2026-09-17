package io.github.topher6835.mediacompare.analysis;

public sealed interface MediaMetadataResult permits AvailableMediaMetadata, UnsupportedMediaMetadata {

    int CURRENT_VERSION = 1;

    int version();

    MediaMetadataOutcome outcome();
}
