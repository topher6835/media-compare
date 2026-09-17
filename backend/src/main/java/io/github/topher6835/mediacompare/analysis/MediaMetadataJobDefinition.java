package io.github.topher6835.mediacompare.analysis;

public final class MediaMetadataJobDefinition {

    public static final String JOB_TYPE = "MEDIA_METADATA";
    public static final long EXECUTION_VERSION = 1;
    public static final String IMAGE_METADATA_STAGE = "IMAGE_METADATA";
    public static final int CANDIDATE_BATCH_SIZE = 100;

    private MediaMetadataJobDefinition() {
    }
}
