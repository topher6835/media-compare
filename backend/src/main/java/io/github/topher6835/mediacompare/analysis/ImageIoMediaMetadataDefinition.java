package io.github.topher6835.mediacompare.analysis;

public final class ImageIoMediaMetadataDefinition {

    public static final String ANALYZER_ID = "builtin.imageio";
    public static final String ANALYZER_VERSION = "1";
    public static final long CONFIGURATION_VERSION = 1;
    public static final String CONFIGURATION_JSON = "{}";
    public static final String CONFIGURATION_HASH =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    private ImageIoMediaMetadataDefinition() {
    }

    public static MediaMetadataAnalysisDefinition definition() {
        return new MediaMetadataAnalysisDefinition(
                ANALYZER_ID,
                ANALYZER_VERSION,
                CONFIGURATION_VERSION,
                CONFIGURATION_HASH,
                CONFIGURATION_JSON);
    }
}
