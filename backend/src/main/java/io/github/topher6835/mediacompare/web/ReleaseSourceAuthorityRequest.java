package io.github.topher6835.mediacompare.web;

public record ReleaseSourceAuthorityRequest(String windowId) {
    public ReleaseSourceAuthorityRequest {
        new SourceAuthorityWindowRequest(1, windowId);
    }
}
