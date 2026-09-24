package io.github.topher6835.mediacompare.catalog;

public class LocationContextAcceptanceConflictException extends RuntimeException {
    public LocationContextAcceptanceConflictException(String contextId, String reason) {
        super("LocationContext " + contextId + " cannot be accepted: " + reason);
    }
}
