package io.github.topher6835.mediacompare.analysis;

import java.nio.file.Path;

@FunctionalInterface
public interface ImageMetadataExtractor {

    MediaMetadataResult extract(Path file);
}
