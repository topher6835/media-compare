package io.github.topher6835.mediacompare.preview;

public class ThumbnailGenerationException extends RuntimeException {
    public ThumbnailGenerationException(String message) {
        super(message);
    }

    public ThumbnailGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
