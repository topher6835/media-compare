package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingRunService {
    public static final int MAX_SOURCE_IDS = 1000;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final ScanRepository scans;
    private final IndexingRunAcceptance acceptance;
    private final Version2BackgroundIndexingService background;
    private final IndexingRunReadService reads;

    public IndexingRunService(ScanRepository scans, IndexingRunAcceptance acceptance,
            Version2BackgroundIndexingService background, IndexingRunReadService reads) {
        this.scans = scans;
        this.acceptance = acceptance;
        this.background = background;
        this.reads = reads;
    }

    @Transactional(propagation = Propagation.NEVER)
    public StartResult start(String requestKey, List<Long> sourceIds) {
        String key = canonicalKey(requestKey);
        if (sourceIds != null && sourceIds.size() > MAX_SOURCE_IDS) {
            throw new IllegalArgumentException("Too many Source IDs");
        }
        ScanRunService.validateSourceIds(sourceIds);
        List<Long> canonicalSources = sourceIds.stream().sorted().toList();
        var existing = scans.findScanRunIdByRequestKey(key);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), canonicalSources);
        }
        ScanExecutionDetails accepted;
        try {
            accepted = acceptance.accept(key, canonicalSources);
        } catch (DataAccessException exception) {
            if (!IndexingConstraints.uniqueColumn(exception, "scan_run.request_key")) {
                throw exception;
            }
            // The losing acceptance transaction has rolled back before this reload.
            long winner = scans.findScanRunIdByRequestKey(key).orElseThrow(() -> exception);
            return replay(winner, canonicalSources);
        }
        background.submitAccepted(accepted);
        return new StartResult(true, reads.require(accepted.job().scanRunId()));
    }

    private StartResult replay(long scanRunId, List<Long> sourceIds) {
        IndexingRunDetails existing;
        try {
            existing = reads.require(scanRunId);
        } catch (java.util.NoSuchElementException exception) {
            throw new IllegalStateException("Request key has no indexing execution: ScanRun " + scanRunId, exception);
        }
        if (!"INDEX".equals(existing.scanRun().requestType()) || !sourceIds.equals(existing.sourceIds())) {
            throw new Version2ExecutionConflictException("Request key already identifies a different request");
        }
        return new StartResult(false, existing);
    }

    static String canonicalKey(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException("A UUID request key is required");
        }
        return UUID.fromString(value).toString();
    }

    public record StartResult(boolean created, IndexingRunDetails run) {}
}
