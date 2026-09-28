package io.github.topher6835.mediacompare.preview;

public enum PreviewKind {
    SMALL_THUMBNAIL("small-thumbnail"),
    MEDIUM_PREVIEW("medium-preview");

    private final String directory;

    PreviewKind(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }
}
