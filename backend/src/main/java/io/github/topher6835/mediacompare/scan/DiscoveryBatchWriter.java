package io.github.topher6835.mediacompare.scan;

import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileObservation;
import io.github.topher6835.mediacompare.job.JobRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DiscoveryBatchWriter {

    public static final int MAX_BATCH_SIZE = 250;

    private final CatalogRepository catalogRepository;
    private final JobRepository jobRepository;

    public DiscoveryBatchWriter(CatalogRepository catalogRepository, JobRepository jobRepository) {
        this.catalogRepository = catalogRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public void write(long jobId, long discoveryStageId, List<FileObservation> observations,
            long progressCompleted) {
        if (observations.isEmpty() || observations.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Discovery batches must contain between 1 and "
                    + MAX_BATCH_SIZE + " observations");
        }

        observations.forEach(catalogRepository::observeFile);
        requireRows(jobRepository.updateDiscoveryProgress(jobId, discoveryStageId, progressCompleted), 2,
                "update discovery progress");
    }

    private static void requireRows(int actualRows, int expectedRows, String action) {
        if (actualRows != expectedRows) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
