package io.github.topher6835.mediacompare.catalog;

public class LocationContextRetirementConflictException extends RuntimeException {
    public LocationContextRetirementConflictException(String contextId, String reason) {
        super("LocationContext " + contextId + " cannot be retired: " + reason);
    }
}
