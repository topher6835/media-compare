package io.github.topher6835.mediacompare.scan;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileObservation;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScanExecutionService {

    private static final String SCAN_JOB_TYPE = "SCAN";
    private static final String PENDING_STATUS = "PENDING";
    private static final String DISCOVERY_STAGE_TYPE = "DISCOVERY";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final CatalogRepository catalogRepository;
    private final DiscoveryFileWalker fileWalker;
    private final DiscoveryBatchWriter batchWriter;
    private final DiscoveryExecutionState executionState;

    public ScanExecutionService(ScanRepository scanRepository, JobRepository jobRepository,
            CatalogRepository catalogRepository, DiscoveryFileWalker fileWalker,
            DiscoveryBatchWriter batchWriter, DiscoveryExecutionState executionState) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.catalogRepository = catalogRepository;
        this.fileWalker = fileWalker;
        this.batchWriter = batchWriter;
        this.executionState = executionState;
    }

    @Transactional
    public ScanExecutionDetails create(long scanRunId) {
        requireScanRun(scanRunId);
        if (jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE).isPresent()) {
            throw new ScanExecutionAlreadyExistsException(scanRunId);
        }

        long createdAtMs = System.currentTimeMillis();
        Job job = jobRepository.insert(new Job(
                null,
                scanRunId,
                SCAN_JOB_TYPE,
                PENDING_STATUS,
                DISCOVERY_STAGE_TYPE,
                0,
                null,
                0,
                createdAtMs,
                null,
                null,
                null));

        JobStage discoveryStage = jobRepository.insert(new JobStage(
                null,
                job.id(),
                DISCOVERY_STAGE_TYPE,
                PENDING_STATUS,
                0,
                null,
                0,
                createdAtMs,
                null,
                null,
                null));

        return new ScanExecutionDetails(job, List.of(discoveryStage));
    }

    public Optional<ScanExecutionDetails> findByScanRunId(long scanRunId) {
        if (scanRepository.findScanRunById(scanRunId).isEmpty()) {
            return Optional.empty();
        }

        return jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE)
                .map(job -> new ScanExecutionDetails(job, jobRepository.findJobStagesByJobId(job.id())));
    }

    public ScanExecutionDetails executeDiscovery(long scanRunId) {
        DiscoveryPlan plan = preflight(scanRunId);
        executionState.start(scanRunId, plan.job(), plan.discoveryStage(), plan.sources(),
                System.currentTimeMillis());

        long discoveredCount = 0;
        for (DiscoverySource source : plan.sources()) {
            try {
                discoveredCount = walkSource(plan.job(), plan.discoveryStage(), source, discoveredCount);
            } catch (IOException | InvalidPathException | SecurityException exception) {
                String errorMessage = discoveryError(source.source(), exception);
                executionState.fail(scanRunId, plan.job(), plan.discoveryStage(), plan.sources(), source,
                        System.currentTimeMillis(), errorMessage);
                throw new DiscoveryExecutionFailedException(errorMessage, exception);
            }
        }

        executionState.complete(plan.job(), plan.discoveryStage(), plan.sources(), discoveredCount,
                System.currentTimeMillis());
        return findByScanRunId(scanRunId).orElseThrow();
    }

    private DiscoveryPlan preflight(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> scanRunSources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);

        Map<Long, Source> currentSources = new HashMap<>();
        for (Source source : catalogRepository.findAllSources()) {
            currentSources.put(source.id(), source);
        }

        List<DiscoverySource> sources = new ArrayList<>();
        for (ScanRunSource scanRunSource : scanRunSources) {
            Source source = currentSources.get(scanRunSource.sourceId());
            if (source == null) {
                throw new DiscoveryConflictException(
                        "Source " + scanRunSource.sourceId() + " no longer exists");
            }
            if (source.locationRevision() != scanRunSource.sourceLocationRevision()) {
                throw new DiscoveryConflictException(
                        "Source " + source.id() + " configuration changed after the ScanRun was created");
            }
            if (!PENDING_STATUS.equals(scanRunSource.status())) {
                throw new DiscoveryConflictException("Source " + source.id() + " is not pending discovery");
            }
            sources.add(new DiscoverySource(source, scanRunSource, scanRunSource.traversalGeneration() + 1));
        }

        Job job = jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        if (!PENDING_STATUS.equals(scanRun.status())
                || !SCAN_JOB_TYPE.equals(job.jobType())
                || !PENDING_STATUS.equals(job.status())
                || !DISCOVERY_STAGE_TYPE.equals(job.currentStageType())) {
            throw new DiscoveryConflictException("Execution is not eligible to start DISCOVERY");
        }

        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(job.id(), DISCOVERY_STAGE_TYPE)
                .orElseThrow(() -> new DiscoveryConflictException("DISCOVERY stage does not exist"));
        if (!PENDING_STATUS.equals(discoveryStage.status())) {
            throw new DiscoveryConflictException("DISCOVERY has already started or completed");
        }

        return new DiscoveryPlan(job, discoveryStage, List.copyOf(sources));
    }

    private long walkSource(Job job, JobStage discoveryStage, DiscoverySource source, long initialCount)
            throws IOException {
        List<FileObservation> batch = new ArrayList<>(DiscoveryBatchWriter.MAX_BATCH_SIZE);
        long[] discoveredCount = { initialCount };

        fileWalker.walk(Path.of(source.source().rootPath()), discoveredFile -> {
            long observedAtMs = System.currentTimeMillis();
            batch.add(new FileObservation(
                    source.source().id(),
                    discoveredFile.relativePath(),
                    discoveredFile.relativePath(),
                    discoveredFile.sizeBytes(),
                    discoveredFile.modifiedTimeEpochSecond(),
                    discoveredFile.modifiedTimeNano(),
                    observedAtMs,
                    source.scanRunSource().id(),
                    source.traversalGeneration()));
            if (batch.size() == DiscoveryBatchWriter.MAX_BATCH_SIZE) {
                discoveredCount[0] = flushBatch(job, discoveryStage, batch, discoveredCount[0]);
            }
        });
        if (!batch.isEmpty()) {
            discoveredCount[0] = flushBatch(job, discoveryStage, batch, discoveredCount[0]);
        }
        return discoveredCount[0];
    }

    private long flushBatch(Job job, JobStage discoveryStage, List<FileObservation> batch, long previousCount) {
        long progressCompleted = previousCount + batch.size();
        batchWriter.write(job.id(), discoveryStage.id(), List.copyOf(batch), progressCompleted);
        batch.clear();
        return progressCompleted;
    }

    private static String discoveryError(Source source, Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        }
        return "Discovery failed for Source " + source.id() + ": " + detail;
    }

    private void requireScanRun(long scanRunId) {
        if (scanRepository.findScanRunById(scanRunId).isEmpty()) {
            throw new NoSuchElementException("ScanRun " + scanRunId + " does not exist");
        }
    }

    private record DiscoveryPlan(Job job, JobStage discoveryStage, List<DiscoverySource> sources) {
    }
}
