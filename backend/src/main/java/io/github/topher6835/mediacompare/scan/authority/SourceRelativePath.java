package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Component-based portable relative path, using today's exact-spelling path-key rule. */
public final class SourceRelativePath {
    private SourceRelativePath() {
    }

    public static String from(LocationPath sourceRoot, LocationPath fileLocation) {
        Objects.requireNonNull(sourceRoot, "Source root");
        Objects.requireNonNull(fileLocation, "File location");
        if (!sourceRoot.contains(fileLocation)
                || sourceRoot.components().size() == fileLocation.components().size()) {
            throw new IllegalArgumentException("File must be a strict structured descendant of Source root");
        }
        return String.join("/", fileLocation.components().subList(
                sourceRoot.components().size(), fileLocation.components().size()));
    }
}
