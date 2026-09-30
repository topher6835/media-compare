package io.github.topher6835.mediacompare.session;

/** A Source cannot contain the disposable Session or be contained by it. */
public final class SourceWorkspaceOverlapException extends IllegalArgumentException {
    public SourceWorkspaceOverlapException() {
        super("Source root must not overlap the active Session folder");
    }
}
