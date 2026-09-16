package io.github.topher6835.mediacompare.catalog;

import java.util.Locale;

public final class FileExtensionNormalizer {

    private FileExtensionNormalizer() {
    }

    public static String fromRelativePath(String relativePath) {
        int basenameStart = relativePath.lastIndexOf('/') + 1;
        int lastDot = relativePath.lastIndexOf('.');
        if (lastDot <= basenameStart || lastDot == relativePath.length() - 1) {
            return null;
        }
        return relativePath.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    public static String normalizeFilterValue(String extension) {
        return extension.toLowerCase(Locale.ROOT);
    }

    public static String toApiValue(String extensionKey) {
        return extensionKey.toUpperCase(Locale.ROOT);
    }
}
