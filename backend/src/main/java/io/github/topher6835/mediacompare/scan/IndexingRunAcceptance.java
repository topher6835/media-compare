package io.github.topher6835.mediacompare.scan;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingRunAcceptance {
    private final ScanRepository scans;
    private final ScanRunService requests;
    private final Version2ScanExecutionService executions;

    public IndexingRunAcceptance(ScanRepository scans, ScanRunService requests, Version2ScanExecutionService executions) {
        this.scans = scans;
        this.requests = requests;
        this.executions = executions;
    }

    @Transactional
    public ScanExecutionDetails accept(String key, List<Long> sourceIds) {
        scans.reserveExecutionWrite();
        ScanRunDetails request = requests.create(sourceIds, key);
        return executions.create(request.scanRun().id());
    }
}
