package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry.ReleaseState;

public record ReleaseSourceAuthorityResponse(SourceResponse source, ReleaseState releaseState,
        boolean otherWindowsOnVolumeRemain) { }
