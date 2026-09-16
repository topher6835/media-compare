package io.github.topher6835.mediacompare.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanRunService {

    private static final String INDEX_REQUEST_TYPE = "INDEX";
    private static final String PENDING_STATUS = "PENDING";
    private static final long INITIAL_OPTIONS_VERSION = 1;
    private static final String INITIAL_OPTIONS_JSON = "{}";

    private final ScanRepository scanRepository;
    private final CatalogRepository catalogRepository;

    public ScanRunService(ScanRepository scanRepository, CatalogRepository catalogRepository) {
        this.scanRepository = scanRepository;
        this.catalogRepository = catalogRepository;
    }

    @Transactional
    public ScanRunDetails create(List<Long> sourceIds) {
        validateSourceIds(sourceIds);

        List<Source> sources = sourceIds.stream()
                .map(this::findRequestedSource)
                .sorted(Comparator.comparingLong(Source::id))
                .toList();

        long createdAtMs = System.currentTimeMillis();
        ScanRun scanRun = scanRepository.insert(new ScanRun(
                null,
                null,
                INDEX_REQUEST_TYPE,
                PENDING_STATUS,
                null,
                INITIAL_OPTIONS_VERSION,
                INITIAL_OPTIONS_JSON,
                createdAtMs,
                null,
                null,
                null));

        List<ScanRunSource> scanRunSources = new ArrayList<>();
        for (Source source : sources) {
            scanRunSources.add(scanRepository.insert(new ScanRunSource(
                    null,
                    scanRun.id(),
                    source.id(),
                    PENDING_STATUS,
                    source.locationRevision(),
                    0,
                    null,
                    null,
                    null,
                    null)));
        }

        return new ScanRunDetails(scanRun, List.copyOf(scanRunSources));
    }

    public Optional<ScanRunDetails> findById(long id) {
        return scanRepository.findScanRunById(id)
                .map(scanRun -> new ScanRunDetails(
                        scanRun,
                        scanRepository.findScanRunSourcesByScanRunId(scanRun.id())));
    }

    private Source findRequestedSource(long sourceId) {
        return catalogRepository.findSourceById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source " + sourceId + " does not exist"));
    }

    private static void validateSourceIds(List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            throw new IllegalArgumentException("At least one Source ID is required");
        }

        Set<Long> uniqueSourceIds = new HashSet<>();
        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId <= 0) {
                throw new IllegalArgumentException("Source IDs must be positive");
            }
            if (!uniqueSourceIds.add(sourceId)) {
                throw new IllegalArgumentException("Duplicate Source IDs are not allowed");
            }
        }
    }
}
