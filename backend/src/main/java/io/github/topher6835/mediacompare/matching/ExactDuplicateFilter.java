package io.github.topher6835.mediacompare.matching;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.topher6835.mediacompare.catalog.FileCategory;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;

public record ExactDuplicateFilter(
        Set<FileCategory> fileCategories,
        Set<String> extensionKeys,
        Set<String> effectiveExtensionKeys,
        boolean active) {

    static final int MAX_FILTER_VALUES = 100;
    private static final int MAX_EXTENSION_LENGTH = 255;

    public ExactDuplicateFilter {
        fileCategories = Set.copyOf(fileCategories);
        extensionKeys = Set.copyOf(extensionKeys);
        effectiveExtensionKeys = Set.copyOf(effectiveExtensionKeys);
    }

    public static ExactDuplicateFilter none() {
        return new ExactDuplicateFilter(Set.of(), Set.of(), Set.of(), false);
    }

    public static ExactDuplicateFilter from(
            List<String> requestedCategories, List<String> requestedExtensions) {
        List<String> categories = requestedCategories == null ? List.of() : requestedCategories;
        List<String> extensions = requestedExtensions == null ? List.of() : requestedExtensions;
        if (categories.size() + extensions.size() > MAX_FILTER_VALUES) {
            throw new IllegalArgumentException("At most " + MAX_FILTER_VALUES + " filter values are allowed");
        }

        Set<FileCategory> parsedCategories = new LinkedHashSet<>();
        for (String category : categories) {
            if (category == null || category.isBlank()) {
                throw new IllegalArgumentException("File category must not be blank");
            }
            try {
                parsedCategories.add(FileCategory.valueOf(category.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported file category: " + category);
            }
        }

        Set<String> normalizedExtensions = new LinkedHashSet<>();
        for (String extension : extensions) {
            validateExtension(extension);
            normalizedExtensions.add(FileExtensionNormalizer.normalizeFilterValue(extension));
        }

        boolean active = !categories.isEmpty() || !extensions.isEmpty();
        if (!active) {
            return none();
        }

        Set<String> effective = new LinkedHashSet<>();
        if (!parsedCategories.isEmpty()) {
            parsedCategories.stream()
                    .map(FileCategory::extensionKeys)
                    .flatMap(Collection::stream)
                    .forEach(effective::add);
        }
        if (parsedCategories.isEmpty()) {
            effective.addAll(normalizedExtensions);
        } else if (!normalizedExtensions.isEmpty()) {
            effective.retainAll(normalizedExtensions);
        }

        return new ExactDuplicateFilter(
                parsedCategories, normalizedExtensions, effective, true);
    }

    public boolean matches(String extensionKey) {
        return !active || extensionKey != null && effectiveExtensionKeys.contains(extensionKey);
    }

    private static void validateExtension(String extension) {
        if (extension == null || extension.isBlank() || !extension.equals(extension.trim())) {
            throw new IllegalArgumentException("Extension must not be blank or padded with whitespace");
        }
        if (extension.codePointCount(0, extension.length()) > MAX_EXTENSION_LENGTH) {
            throw new IllegalArgumentException("Extension is too long");
        }
        if (extension.indexOf('.') >= 0 || extension.indexOf('/') >= 0 || extension.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Extension must not include a dot or path separator");
        }
        if (extension.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Extension must not include control characters");
        }
    }
}
