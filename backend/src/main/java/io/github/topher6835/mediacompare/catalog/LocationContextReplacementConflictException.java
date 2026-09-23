package io.github.topher6835.mediacompare.catalog;

public class LocationContextReplacementConflictException extends RuntimeException {
    public LocationContextReplacementConflictException(String contextId, String reason) {
        super("LocationContext " + contextId + " cannot be replaced: " + reason);
    }
}
