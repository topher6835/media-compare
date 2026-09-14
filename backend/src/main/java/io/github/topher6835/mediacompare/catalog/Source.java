package io.github.topher6835.mediacompare.catalog;

public record Source(
        Long id,
        String name,
        String rootPath,
        String rootPathKey,
        long locationRevision,
        long createdAtMs,
        long updatedAtMs) {
}
