package io.github.topher6835.mediacompare.analysis;

public class ImageMetadataExtractionException extends RuntimeException {

    public ImageMetadataExtractionException(String detail, Throwable cause) {
        super("Image metadata extraction failed: " + detail, cause);
    }
}
