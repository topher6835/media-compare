package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.catalog.Source;

public record SourceResponse(
        long id,
        String name,
        String rootPath,
        long locationRevision,
        long createdAtMs,
        long updatedAtMs) {

    public static SourceResponse from(Source source) {
        return new SourceResponse(
                source.id(),
                source.name(),
                source.rootPath(),
                source.locationRevision(),
                source.createdAtMs(),
                source.updatedAtMs());
    }
}
