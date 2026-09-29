package io.github.topher6835.mediacompare.preview;

import java.util.Objects;

public record ThumbnailGenerationResult(Outcome outcome, PreviewAsset asset) {
    public enum Outcome { REUSED, GENERATED, UNSUPPORTED }

    public ThumbnailGenerationResult {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.UNSUPPORTED) != (asset == null)) {
            throw new IllegalArgumentException("Only unsupported generation has no asset");
        }
    }
}
