package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.job.JobRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReconciliationWriter {

    private final CatalogRepository catalogRepository;
    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;

    public ReconciliationWriter(CatalogRepository catalogRepository, ScanRepository scanRepository,
            JobRepository jobRepository) {
        this.catalogRepository = catalogRepository;
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void reconcile(long jobId, long reconciliationStageId, ScanRunSource source,
            long progressCompleted, long completedAtMs) {
        reconcile(jobId, reconciliationStageId, ScanExecutionDefinition.VERSION_1,
                source, progressCompleted, completedAtMs);
    }

    @Transactional
    public void reconcile(long jobId, long reconciliationStageId, long executionVersion,
            ScanRunSource source, long progressCompleted, long completedAtMs) {
        catalogRepository.markUnseenPresentFilesMissing(
                source.sourceId(), source.id(), source.traversalGeneration());
        requireOne(scanRepository.completeSourceReconciliation(
                source.id(), source.traversalGeneration(), completedAtMs),
                "complete Source reconciliation");
        requireRows(jobRepository.updateReconciliationProgress(
                jobId, reconciliationStageId, executionVersion, progressCompleted), 2,
                "update reconciliation progress");
    }

    private static void requireOne(int rows, String action) {
        requireRows(rows, 1, action);
    }

    private static void requireRows(int actualRows, int expectedRows, String action) {
        if (actualRows != expectedRows) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
