package io.github.topher6835.mediacompare.preview;

import java.util.Objects;

import org.springframework.http.MediaType;

/** Metadata for an already published successful file. Old evidence is retained as disposable cache. */
public record PreviewAsset(
        Long id,
        String assetKey,
        PreviewSourceEvidence evidence,
        PreviewKind kind,
        PreviewDefinition definition,
        String relativePath,
        String mediaType,
        int pixelWidth,
        int pixelHeight,
        long assetSizeBytes,
        long createdAtMs) {

    public PreviewAsset {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("Asset ID must be positive");
        }
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(definition, "definition");
        PreviewAssetKey.requireValid(assetKey);
        if (!assetKey.equals(PreviewAssetKey.compute(evidence, kind, definition))) {
            throw new IllegalArgumentException("Asset key does not match its evidence and definition");
        }
        PreviewCacheLayout.requireAssetPath(relativePath, kind, assetKey);
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        MediaType parsed = MediaType.parseMediaType(mediaType);
        if (!parsed.isConcrete()) {
            throw new IllegalArgumentException("mediaType must be concrete");
        }
        if (pixelWidth <= 0 || pixelHeight <= 0 || assetSizeBytes <= 0 || createdAtMs < 0) {
            throw new IllegalArgumentException("Dimensions/asset size must be positive and creation time nonnegative");
        }
    }
}
