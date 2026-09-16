package io.github.topher6835.mediacompare.catalog;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

public enum FileCategory {
    PHOTO(Set.of(
            "jpg", "jpeg", "png", "heic", "heif", "avif", "webp", "gif", "bmp", "tif", "tiff",
            "dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2")),
    VIDEO(Set.of(
            "mp4", "mov", "m4v", "mkv", "avi", "webm", "mpg", "mpeg", "mts", "m2ts", "3gp", "wmv")),
    DOCUMENT(Set.of(
            "pdf", "txt", "md", "rtf", "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "odt", "ods", "odp"));

    private final Set<String> extensionKeys;

    FileCategory(Set<String> extensionKeys) {
        this.extensionKeys = extensionKeys;
    }

    public Set<String> extensionKeys() {
        return extensionKeys;
    }

    public static Optional<FileCategory> fromExtensionKey(String extensionKey) {
        if (extensionKey == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(category -> category.extensionKeys.contains(extensionKey))
                .findFirst();
    }
}
