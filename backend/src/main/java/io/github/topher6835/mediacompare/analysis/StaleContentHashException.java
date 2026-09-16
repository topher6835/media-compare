package io.github.topher6835.mediacompare.analysis;

public class StaleContentHashException extends RuntimeException {

    public StaleContentHashException(long fileEntryId, String detail) {
        super("FileEntry " + fileEntryId + " is stale for content hashing: " + detail);
    }
}
