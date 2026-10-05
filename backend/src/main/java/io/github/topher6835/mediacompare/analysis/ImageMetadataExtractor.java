package io.github.topher6835.mediacompare.analysis;

import java.nio.file.Path;

@FunctionalInterface
public interface ImageMetadataExtractor {

    MediaMetadataResult extract(Path file);
    default MediaMetadataResult extract(javax.imageio.stream.ImageInputStream input) {
        throw new UnsupportedOperationException("Supplied protected input is unsupported");
    }
}
