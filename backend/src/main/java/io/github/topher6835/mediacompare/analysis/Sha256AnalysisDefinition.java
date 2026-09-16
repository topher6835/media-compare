package io.github.topher6835.mediacompare.analysis;

public final class Sha256AnalysisDefinition {

    public static final String ANALYSIS_TYPE = "CONTENT_HASH";
    public static final String ANALYZER_ID = "builtin.sha256";
    public static final String ANALYZER_VERSION = "1";
    public static final long CONFIGURATION_VERSION = 1;
    public static final String CONFIGURATION_JSON = "{}";
    public static final String CONFIGURATION_HASH =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    public static final String ALGORITHM = "SHA-256";

    private Sha256AnalysisDefinition() {
    }

    public static boolean isValidDigest(String digestHex) {
        return digestHex != null && digestHex.matches("[0-9a-f]{64}");
    }
}
