package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;

/** Bounded failure category plus immediately captured error; contains no native handle. */
public final class WindowsFileAccessException extends IOException {
    public enum Reason { UNSUPPORTED, UNAVAILABLE, UNCERTAIN, NATIVE_ERROR }
    private final Reason reason;
    private final Integer nativeError;

    public WindowsFileAccessException(Reason reason, String message) {
        super(message);
        this.reason = reason;
        this.nativeError = null;
    }

    WindowsFileAccessException(String operation, int error) {
        super(operation + " failed (Windows error " + error + ")");
        this.nativeError = error;
        this.reason = switch (error) {
            case 2, 3 -> Reason.UNAVAILABLE;
            case 5, 6, 32, 33 -> Reason.UNCERTAIN;
            default -> Reason.NATIVE_ERROR;
        };
    }

    public Reason reason() { return reason; }
    public Integer nativeError() { return nativeError; }
}
