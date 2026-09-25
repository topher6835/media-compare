package io.github.topher6835.mediacompare.catalog;

public class SourceUnbindingConflictException extends RuntimeException {
    public SourceUnbindingConflictException(long sourceId, String reason) {
        super("Source " + sourceId + " cannot be unbound: " + reason);
    }
}
