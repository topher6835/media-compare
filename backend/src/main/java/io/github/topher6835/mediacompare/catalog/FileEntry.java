package io.github.topher6835.mediacompare.catalog;

public record FileEntry(
        Long id,
        long sourceId,
        String relativePath,
        String pathKey,
        String extensionKey,
        Long currentContentId,
        String presenceStatus,
        long sizeBytes,
        Long modifiedTimeEpochSecond,
        Integer modifiedTimeNano,
        long observationRevision,
        long firstSeenAtMs,
        long lastSeenAtMs,
        Long lastSeenScanRunSourceId,
        Long lastSeenTraversalGeneration) {

    public FileEntry(
            Long id,
            long sourceId,
            String relativePath,
            String pathKey,
            Long currentContentId,
            String presenceStatus,
            long sizeBytes,
            Long modifiedTimeEpochSecond,
            Integer modifiedTimeNano,
            long observationRevision,
            long firstSeenAtMs,
            long lastSeenAtMs,
            Long lastSeenScanRunSourceId,
            Long lastSeenTraversalGeneration) {
        this(id, sourceId, relativePath, pathKey,
                FileExtensionNormalizer.fromRelativePath(relativePath),
                currentContentId, presenceStatus, sizeBytes, modifiedTimeEpochSecond,
                modifiedTimeNano, observationRevision, firstSeenAtMs, lastSeenAtMs,
                lastSeenScanRunSourceId, lastSeenTraversalGeneration);
    }
}
