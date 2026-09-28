package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviewCacheLayoutTests {
    @TempDir Path directory;

    @Test
    void usesPortableKindAndTwoLevelsOfHexFanoutWithoutCreatingAnything() {
        String key = "abcd" + "0".repeat(60);
        assertEquals("small-thumbnail/ab/cd/" + key + ".webp",
                PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "webp"));
        assertEquals("medium-preview/ab/cd/" + key + ".png",
                PreviewCacheLayout.relativePath(PreviewKind.MEDIUM_PREVIEW, key, "png"));
        Path absent = directory.resolve("absent");
        new PreviewCacheResolver(absent.toString());
        assertFalse(Files.exists(absent));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".jpg", "JPG", "../jpg", "j/pg", "jpg\\x", "jpg:stream", "abcdefghijkl", "é"})
    void rejectsExtensionsInsteadOfRepairingThem(String extension) {
        assertThrows(IllegalArgumentException.class,
                () -> PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, "a".repeat(64), extension));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../a", "a/../b", "/a", "a/", "a//b", "./a", "a/./b", "a..b",
            "C:/a", "C:\\a", "\\\\server\\file", "a\\b", "a%2fb", "a\u0000b"})
    void rejectsUnsafePortablePaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> PreviewCacheLayout.requireSafeRelativePath(path));
    }

    @Test
    void rechecksFileSizeAndRegularFileBeforeOpening() throws Exception {
        var resolver = new PreviewCacheResolver(directory.toString());
        Files.write(directory.resolve("asset.png"), new byte[] {1,2,3});
        assertDoesNotThrow(() -> resolver.requireRegularFile("asset.png", 3));
        try (var stream = resolver.open("asset.png", 3)) {
            assertArrayEquals(new byte[] {1,2,3}, stream.readAllBytes());
        }
        assertThrows(IOException.class, () -> resolver.requireRegularFile("asset.png", 4));
        assertThrows(IOException.class, () -> resolver.open("asset.png", 4));
        Files.createDirectory(directory.resolve("folder"));
        assertThrows(IOException.class, () -> resolver.open("folder", 3));
        assertThrows(IOException.class, () -> resolver.open("missing.png", 3));
    }

    @Test
    void openRejectsAFileReplacedByASymbolicLinkAfterTheInitialCheck() throws Exception {
        var resolver = new PreviewCacheResolver(directory.toString());
        Path file = directory.resolve("asset.png");
        Files.write(file, new byte[] {1, 2, 3});
        resolver.requireRegularFile("asset.png", 3);
        Path outside = directory.resolve("other.png");
        Files.write(outside, new byte[] {4, 5, 6});
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Host does not permit symbolic link creation");
        }
        assertThrows(IOException.class, () -> resolver.open("asset.png", 3));
    }
}
