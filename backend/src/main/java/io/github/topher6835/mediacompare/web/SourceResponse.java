package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.catalog.SourceAuthorityProjection;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourcePreparationState;

public record SourceResponse(
        long id,
        String name,
        String rootPath,
        SourcePreparationState preparationState,
        long locationRevision,
        FileSystemProfile filesystemProfile,
        Boolean liveAuthorityAvailable,
        String liveAuthorityWindowId,
        long createdAtMs,
        long updatedAtMs) {

    public static SourceResponse from(Source source) {
        return from(source, new SourceAuthorityProjection.Authority(null, null, null));
    }

    public static SourceResponse from(Source source, SourceAuthorityProjection.Authority authority) {
        return new SourceResponse(
                source.id(),
                source.name(),
                source.rootPath(),
                SourcePreparationState.from(source),
                source.locationRevision(),
                authority.filesystemProfile(), authority.liveAuthorityAvailable(), authority.liveAuthorityWindowId(),
                source.createdAtMs(),
                source.updatedAtMs());
    }
}
