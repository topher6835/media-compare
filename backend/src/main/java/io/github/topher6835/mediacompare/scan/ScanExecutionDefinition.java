package io.github.topher6835.mediacompare.scan;

public final class ScanExecutionDefinition {

    public static final String JOB_TYPE = "SCAN";
    public static final long VERSION_1 = 1;
    public static final long VERSION_2 = 2;
    public static final long VERSION_3 = 3;

    public static final String DISCOVERY = "DISCOVERY";
    public static final String RECONCILIATION = "RECONCILIATION";
    public static final String CONTENT_ASSIGNMENT = "CONTENT_ASSIGNMENT";
    public static final String CONTENT_HASHING = "CONTENT_HASHING";

    private ScanExecutionDefinition() {
    }
}
