package io.github.topher6835.mediacompare.catalog;

public class StaleContentAssignmentException extends RuntimeException {

    public StaleContentAssignmentException(long fileEntryId) {
        super("FileEntry " + fileEntryId + " changed before ContentRecord publication");
    }
}
