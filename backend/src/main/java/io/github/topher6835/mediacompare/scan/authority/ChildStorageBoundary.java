package io.github.topher6835.mediacompare.scan.authority;

/** Host-supplied path classification: any child mount is UNSUPPORTED, even with the same UUID. */
public enum ChildStorageBoundary {
    SAME_ACCEPTED_VOLUME, UNSUPPORTED, UNCERTAIN, UNAVAILABLE
}
