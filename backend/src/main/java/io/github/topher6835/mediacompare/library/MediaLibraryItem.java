package io.github.topher6835.mediacompare.library;

/** One physical FileEntry, with a representative current Source route for presentation only. */
public record MediaLibraryItem(long fileEntryId, long contentRecordId, long sourceId, String sourceName,
        String relativePath, String displayName, String extensionKey, long sizeBytes, String format,
        int encodedWidth, int encodedHeight, long sourceCount, GenerationSupport generationSupport,
        ThumbnailReference thumbnail) {
    public enum GenerationSupport { SUPPORTED, UNSUPPORTED }
}
