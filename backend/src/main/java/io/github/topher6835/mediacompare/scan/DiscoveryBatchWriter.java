package io.github.topher6835.mediacompare.scan;

import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileObservation;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DiscoveryBatchWriter {

    public static final int MAX_BATCH_SIZE = 250;

    private final CatalogRepository catalogRepository;
    private final JobRepository jobRepository;
    private final SourceMembershipPublicationService memberships;

    public DiscoveryBatchWriter(CatalogRepository catalogRepository, JobRepository jobRepository,
            SourceMembershipPublicationService memberships) {
        this.catalogRepository = catalogRepository;
        this.jobRepository = jobRepository;
        this.memberships = memberships;
    }

    @Transactional
    public void writeResolved(long jobId, long discoveryStageId, long scanRunSourceId,
            long generation, List<ResolvedFileCandidate> candidates, long progressCompleted) {
        if (candidates.isEmpty() || candidates.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Discovery batches must contain between 1 and "
                    + MAX_BATCH_SIZE + " candidates");
        }
        for (ResolvedFileCandidate candidate : candidates) {
            memberships.publish(candidate, scanRunSourceId, generation, System.currentTimeMillis());
        }
        requireRows(jobRepository.updateDiscoveryProgress(
                jobId, discoveryStageId, ScanExecutionDefinition.VERSION_3, progressCompleted), 2,
                "update v3 discovery progress");
    }

    @Transactional
    public void write(long jobId, long discoveryStageId, List<FileObservation> observations,
            long progressCompleted) {
        write(jobId, discoveryStageId, ScanExecutionDefinition.VERSION_1, observations, progressCompleted);
    }

    @Transactional
    public void write(long jobId, long discoveryStageId, long executionVersion,
            List<FileObservation> observations, long progressCompleted) {
        if (observations.isEmpty() || observations.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Discovery batches must contain between 1 and "
                    + MAX_BATCH_SIZE + " observations");
        }

        observations.forEach(catalogRepository::observeFile);
        requireRows(jobRepository.updateDiscoveryProgress(
                jobId, discoveryStageId, executionVersion, progressCompleted), 2,
                "update discovery progress");
    }

    private static void requireRows(int actualRows, int expectedRows, String action) {
        if (actualRows != expectedRows) {
            throw new IllegalStateException("Could not " + action);
        }
    }
}
