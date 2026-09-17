package io.github.topher6835.mediacompare.analysis;

public class CurrentImageMetadataExtractionException extends ImageMetadataExtractionException {

    private final MediaMetadataFileCandidate candidate;
    private final long startedAtMs;

    public CurrentImageMetadataExtractionException(
            MediaMetadataFileCandidate candidate,
            long startedAtMs,
            ImageMetadataExtractionException cause) {
        super("current occurrence could not be decoded", cause);
        this.candidate = candidate;
        this.startedAtMs = startedAtMs;
    }

    public MediaMetadataFileCandidate candidate() {
        return candidate;
    }

    public long startedAtMs() {
        return startedAtMs;
    }
}
