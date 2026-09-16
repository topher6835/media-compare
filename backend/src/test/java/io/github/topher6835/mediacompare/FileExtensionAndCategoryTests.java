package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import io.github.topher6835.mediacompare.catalog.FileCategory;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;

import org.junit.jupiter.api.Test;

class FileExtensionAndCategoryTests {

    @Test
    void normalizesExtensionsFromPortableRelativePaths() {
        Map<String, String> expected = Map.of(
                "photo.JPG", "jpg",
                "photo.jpg", "jpg",
                "photo.Jpg", "jpg",
                ".config.json", "json",
                "folder/archive.tar.gz", "gz",
                "folder/unknown.XYZ", "xyz");

        expected.forEach((path, extension) ->
                assertEquals(extension, FileExtensionNormalizer.fromRelativePath(path), path));
        assertNull(FileExtensionNormalizer.fromRelativePath(".gitignore"));
        assertNull(FileExtensionNormalizer.fromRelativePath("folder/.gitignore"));
        assertNull(FileExtensionNormalizer.fromRelativePath("photo."));
        assertNull(FileExtensionNormalizer.fromRelativePath("README"));
    }

    @Test
    void classifiesOnlyTheSupportedTechnicalCategories() {
        assertEquals(FileCategory.PHOTO, FileCategory.fromExtensionKey("jpg").orElseThrow());
        assertEquals(FileCategory.PHOTO, FileCategory.fromExtensionKey("jpeg").orElseThrow());
        assertEquals(FileCategory.PHOTO, FileCategory.fromExtensionKey("heic").orElseThrow());
        assertEquals(FileCategory.PHOTO, FileCategory.fromExtensionKey("cr3").orElseThrow());
        assertEquals(FileCategory.VIDEO, FileCategory.fromExtensionKey("mp4").orElseThrow());
        assertEquals(FileCategory.VIDEO, FileCategory.fromExtensionKey("m2ts").orElseThrow());
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromExtensionKey("pdf").orElseThrow());
        assertEquals(FileCategory.DOCUMENT, FileCategory.fromExtensionKey("docx").orElseThrow());
        assertTrue(FileCategory.fromExtensionKey("mp3").isEmpty());
        assertTrue(FileCategory.fromExtensionKey("zip").isEmpty());
        assertTrue(FileCategory.fromExtensionKey("svg").isEmpty());
        assertTrue(FileCategory.fromExtensionKey("unknown").isEmpty());
        assertTrue(FileCategory.fromExtensionKey(null).isEmpty());
    }
}
