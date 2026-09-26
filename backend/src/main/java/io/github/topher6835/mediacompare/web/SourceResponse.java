package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourcePreparationState;

public record SourceResponse(
        long id,
        String name,
        String rootPath,
        SourcePreparationState preparationState,
        long locationRevision,
        long createdAtMs,
        long updatedAtMs) {

    public static SourceResponse from(Source source) {
        return new SourceResponse(
                source.id(),
                source.name(),
                source.rootPath(),
                SourcePreparationState.from(source),
                source.locationRevision(),
                source.createdAtMs(),
                source.updatedAtMs());
    }
}
