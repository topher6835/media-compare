package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingRunAcceptance {
    private final ScanRepository scans;
    private final ScanRunService requests;
    private final Version3ScanExecutionService executions;
    private final ExfatScanBundles bundles;

    public IndexingRunAcceptance(ScanRepository scans, ScanRunService requests, Version3ScanExecutionService executions,
            ExfatScanBundles bundles) {
        this.scans = scans;
        this.requests = requests;
        this.executions = executions;
        this.bundles = bundles;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public ScanExecutionDetails accept(String key, List<Long> sourceIds) {
        return accept(key, sourceIds, List.of());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public ScanExecutionDetails accept(String key, List<Long> sourceIds,
            List<SourceAuthorityWindowRequest> authorityWindows) {
        return bundles.admit(sourceIds, authorityWindows, prepared -> {
            scans.reserveExecutionWrite();
            ScanRunDetails request = requests.create(sourceIds, key);
            return executions.create(request.scanRun().id(), prepared);
        });
    }
}
