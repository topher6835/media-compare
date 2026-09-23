package io.github.topher6835.mediacompare.catalog;

public class LocationContextStructuralOverlapException extends RuntimeException {
    private final String existingContextId;

    public LocationContextStructuralOverlapException(String existingContextId) {
        super("Active LocationContext anchor overlaps context " + existingContextId);
        this.existingContextId = existingContextId;
    }

    public String existingContextId() {
        return existingContextId;
    }
}
