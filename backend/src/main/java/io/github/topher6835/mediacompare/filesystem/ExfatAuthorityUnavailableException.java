package io.github.topher6835.mediacompare.filesystem;

/** Bounded runtime authority loss, distinct from unexpected catalog or infrastructure failures. */
public final class ExfatAuthorityUnavailableException extends IllegalStateException {
    public ExfatAuthorityUnavailableException(String message) { super(message); }
}
