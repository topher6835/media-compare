package io.github.topher6835.mediacompare.catalog;

/** Historical snapshot of one accepted Source binding period. */
public record SourceBindingPeriod(
        Long id,
        long sourceId,
        long boundSourceLocationRevision,
        String locationContextId,
        String rootPathDialect,
        String rootPath,
        String rootPathKey,
        String bindingEvidenceJson,
        long boundAtMs,
        Long unboundSourceLocationRevision,
        Long unboundAtMs) {
}
