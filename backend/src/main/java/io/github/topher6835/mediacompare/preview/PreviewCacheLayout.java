package io.github.topher6835.mediacompare.preview;

import java.util.Objects;

/** Portable persisted paths, independent of the configured root and host path separator. */
public final class PreviewCacheLayout {
    private PreviewCacheLayout() {
    }

    public static String relativePath(PreviewKind kind, String assetKey, String extension) {
        Objects.requireNonNull(kind, "kind");
        PreviewAssetKey.requireValid(assetKey);
        if (extension == null || !extension.matches("[a-z0-9]{1,10}")) {
            throw new IllegalArgumentException("extension must be 1..10 lowercase ASCII letters/digits, without a dot");
        }
        return kind.directory() + "/" + assetKey.substring(0, 2) + "/" + assetKey.substring(2, 4)
                + "/" + assetKey + "." + extension;
    }

    public static void requireSafeRelativePath(String path) {
        if (path == null || path.length() > 512 || !path.matches("[a-z0-9._-]+(/[a-z0-9._-]+)*")
                || path.contains("..")) {
            throw new IllegalArgumentException("Invalid cache-relative path");
        }
        for (String component : path.split("/")) {
            if (component.equals(".")) {
                throw new IllegalArgumentException("Invalid cache-relative path component");
            }
        }
    }

    public static void requireAssetPath(String path, PreviewKind kind, String assetKey) {
        requireSafeRelativePath(path);
        String extension = path.substring(path.lastIndexOf('.') + 1);
        if (!path.equals(relativePath(kind, assetKey, extension))) {
            throw new IllegalArgumentException("Cache path does not match the asset identity/layout");
        }
    }
}
