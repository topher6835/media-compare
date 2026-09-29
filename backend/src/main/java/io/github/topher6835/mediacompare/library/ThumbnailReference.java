package io.github.topher6835.mediacompare.library;

import io.github.topher6835.mediacompare.preview.PreviewAssetKey;

/** PUBLISHED describes current database metadata, not physical cache-file availability. */
public record ThumbnailReference(State state, String assetKey, String url, Integer width, Integer height) {
    public enum State { MISSING, PUBLISHED }

    public static ThumbnailReference missing() {
        return new ThumbnailReference(State.MISSING, null, null, null, null);
    }

    public static ThumbnailReference published(String assetKey, int width, int height) {
        PreviewAssetKey.requireValid(assetKey);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Published thumbnail dimensions are invalid");
        }
        return new ThumbnailReference(State.PUBLISHED, assetKey, "/api/previews/" + assetKey, width, height);
    }
}
