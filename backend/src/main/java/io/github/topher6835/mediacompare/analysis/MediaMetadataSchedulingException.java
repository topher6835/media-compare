package io.github.topher6835.mediacompare.analysis;

public class MediaMetadataSchedulingException extends RuntimeException {

    public MediaMetadataSchedulingException(Throwable cause) {
        super("Media metadata execution could not be scheduled", cause);
    }
}
