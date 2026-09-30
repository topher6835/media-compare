package io.github.topher6835.mediacompare.library;

/** One physical FileEntry, with a representative current Source route for presentation only. */
public record MediaLibraryItem(long fileEntryId, long contentRecordId, long sourceId, String sourceName,
        String relativePath, String displayName, String extensionKey, long sizeBytes, String format,
        Integer encodedWidth, Integer encodedHeight, long sourceCount, GenerationSupport generationSupport,
        ThumbnailReference thumbnail, String absolutePath, ExactSetReference exactSet) {
    public enum GenerationSupport { SUPPORTED, UNSUPPORTED }

    public MediaLibraryItem withExactSet(ExactSetReference reference) {
        return new MediaLibraryItem(fileEntryId, contentRecordId, sourceId, sourceName, relativePath,
                displayName, extensionKey, sizeBytes, format, encodedWidth, encodedHeight,
                sourceCount, generationSupport, thumbnail, absolutePath, reference);
    }
}
