package io.github.topher6835.mediacompare.catalog;

public record Source(
        Long id,
        String name,
        String rootPath,
        String rootPathKey,
        long locationRevision,
        String rootPathDialect,
        String boundLocationContextId,
        String bindingEvidenceJson,
        long createdAtMs,
        long updatedAtMs) {

    public Source(Long id, String name, String rootPath, String rootPathKey, long locationRevision,
            long createdAtMs, long updatedAtMs) {
        this(id, name, rootPath, rootPathKey, locationRevision, null, null, null, createdAtMs, updatedAtMs);
    }
}
