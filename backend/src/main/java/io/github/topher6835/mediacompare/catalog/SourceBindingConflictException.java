package io.github.topher6835.mediacompare.catalog;

public class SourceBindingConflictException extends RuntimeException {
    public SourceBindingConflictException(long sourceId, String reason) {
        super("Source " + sourceId + " cannot be bound: " + reason);
    }
}
