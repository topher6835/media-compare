package io.github.topher6835.mediacompare.catalog;

public class SourceRelocationConflictException extends RuntimeException {
    public SourceRelocationConflictException(long sourceId, String reason) {
        super("Source " + sourceId + " cannot be relocated: " + reason);
    }
}
