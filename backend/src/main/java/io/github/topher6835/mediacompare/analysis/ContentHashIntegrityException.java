package io.github.topher6835.mediacompare.analysis;

public class ContentHashIntegrityException extends RuntimeException {

    public ContentHashIntegrityException(long analysisRecordId, String detail) {
        super("Content hash artifact " + analysisRecordId + " is invalid: " + detail);
    }
}
