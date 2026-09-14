package io.github.topher6835.mediacompare.scan;

public class ScanExecutionAlreadyExistsException extends RuntimeException {

    public ScanExecutionAlreadyExistsException(long scanRunId) {
        super("ScanRun " + scanRunId + " already has a scan execution");
    }
}
