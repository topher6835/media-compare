package io.github.topher6835.mediacompare.analysis;

import java.util.Locale;
import java.util.Objects;

public record ImageMediaMetadata(String format, int width, int height) {

    public ImageMediaMetadata {
        format = requireNormalizedName(format, "Image format");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
    }

    static String requireNormalizedName(String value, String description) {
        Objects.requireNonNull(value, description + " is required");
        if (!value.matches("[a-z0-9][a-z0-9._-]*") || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(description + " must be a normalized lowercase identifier");
        }
        return value;
    }
}
