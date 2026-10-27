package io.github.topher6835.mediacompare.operation;

/** Only application-defined status/code values become public diagnostics. */
public final class CatalogOperationFailure extends RuntimeException {
    private final int status;
    private final String code;

    public CatalogOperationFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
