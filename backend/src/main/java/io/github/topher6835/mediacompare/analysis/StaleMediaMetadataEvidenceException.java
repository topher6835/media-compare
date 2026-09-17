package io.github.topher6835.mediacompare.analysis;

public class StaleMediaMetadataEvidenceException extends RuntimeException {

    public StaleMediaMetadataEvidenceException(long fileEntryId, String detail) {
        super("FileEntry " + fileEntryId + " is stale for media metadata analysis: " + detail);
    }

    public StaleMediaMetadataEvidenceException(long fileEntryId, String detail, Throwable cause) {
        super("FileEntry " + fileEntryId + " is stale for media metadata analysis: " + detail, cause);
    }
}
