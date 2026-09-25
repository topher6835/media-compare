package io.github.topher6835.mediacompare.catalog;

public class SourceRebindingConflictException extends RuntimeException {
    public SourceRebindingConflictException(long sourceId, String reason) {
        super("Source " + sourceId + " cannot be rebound: " + reason);
    }
}
