package io.github.topher6835.mediacompare.scan;

import java.util.Optional;

import org.springframework.stereotype.Service;

/** Read-only compatibility for historical v2 SCAN executions. */
@Service
public class Version2ScanExecutionService {
    private final ScanExecutionService historical;

    public Version2ScanExecutionService(ScanExecutionService historical) {
        this.historical = historical;
    }

    public Optional<ScanExecutionDetails> findByScanRunId(long scanRunId) {
        return historical.findByScanRunIdAndVersion(scanRunId, ScanExecutionDefinition.VERSION_2);
    }

    public ScanExecutionDetails create(long scanRunId) {
        throw new UnsupportedOperationException("New SCAN executions require version 3");
    }

    public ScanExecutionDetails run(long scanRunId) {
        throw new UnsupportedOperationException("Historical v2 SCAN work cannot resume against V6");
    }
}
