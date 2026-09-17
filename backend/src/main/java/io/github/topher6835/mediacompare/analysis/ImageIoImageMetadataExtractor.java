package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

@Component
public class ImageIoImageMetadataExtractor implements ImageMetadataExtractor {

    private static final Map<String, String> CANONICAL_FORMATS = Map.of(
            "jpeg", "jpeg",
            "jpg", "jpeg",
            "png", "png",
            "gif", "gif",
            "bmp", "bmp",
            "tiff", "tiff",
            "tif", "tiff");

    @Override
    public MediaMetadataResult extract(Path file) {
        Objects.requireNonNull(file, "file");
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return unsupported();
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return unsupported();
            }

            ImageReader reader = readers.next();
            try {
                String format = canonicalFormat(reader.getFormatName());
                if (format == null) {
                    return unsupported();
                }
                reader.setInput(input, true, true);
                return new AvailableMediaMetadata(
                        MediaMetadataResult.CURRENT_VERSION,
                        MediaKind.IMAGE,
                        new ImageMediaMetadata(format, reader.getWidth(0), reader.getHeight(0)),
                        null);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ImageMetadataExtractionException extractionException) {
                throw extractionException;
            }
            throw new ImageMetadataExtractionException("ImageIO could not read image metadata", exception);
        }
    }

    private static String canonicalFormat(String formatName) {
        if (formatName == null) {
            return null;
        }
        return CANONICAL_FORMATS.get(formatName.toLowerCase(Locale.ROOT));
    }

    private static UnsupportedMediaMetadata unsupported() {
        return new UnsupportedMediaMetadata(MediaMetadataResult.CURRENT_VERSION);
    }
}
