package io.github.topher6835.mediacompare.catalog;

/** Bounded preparation failure exposed without filesystem or Java exception text. */
public class SourcePreparationException extends RuntimeException {
    public enum Code {
        PATH_UNAVAILABLE,
        PROFILE_UNSUPPORTED,
        EVIDENCE_UNCERTAIN,
        PROBE_ERROR,
        STATE_CHANGED
    }

    private final Code code;

    public SourcePreparationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
