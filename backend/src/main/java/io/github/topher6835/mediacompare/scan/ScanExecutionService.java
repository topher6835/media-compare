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

    private static final String PENDING_STATUS = "PENDING";

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
        if (jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, ScanExecutionDefinition.VERSION_1).isPresent()) {
            throw new ScanExecutionAlreadyExistsException(scanRunId);
        }

        long createdAtMs = System.currentTimeMillis();
        Job job = jobRepository.insert(new Job(
                null,
                scanRunId,
                ScanExecutionDefinition.JOB_TYPE,
                ScanExecutionDefinition.VERSION_1,
                PENDING_STATUS,
                ScanExecutionDefinition.DISCOVERY,
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
                ScanExecutionDefinition.DISCOVERY,
                null,
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

        return findByScanRunIdAndVersion(scanRunId, ScanExecutionDefinition.VERSION_1);
    }

    public Optional<ScanExecutionDetails> findByScanRunIdAndVersion(long scanRunId, long executionVersion) {
        if (scanRepository.findScanRunById(scanRunId).isEmpty()) {
            return Optional.empty();
        }

        return jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, executionVersion)
                .map(job -> new ScanExecutionDetails(job, jobRepository.findJobStagesByJobId(job.id())));
    }

    public ScanExecutionDetails executeDiscovery(long scanRunId) {
        return executeDiscovery(scanRunId, ScanExecutionDefinition.VERSION_1);
    }

    public ScanExecutionDetails executeVersion2Discovery(long scanRunId) {
        return executeDiscovery(scanRunId, ScanExecutionDefinition.VERSION_2);
    }

    private ScanExecutionDetails executeDiscovery(long scanRunId, long executionVersion) {
        DiscoveryPlan plan = preflight(scanRunId, executionVersion);
        executionState.start(scanRunId, plan.job(), plan.discoveryStage(), plan.sources(),
                System.currentTimeMillis());

        long discoveredCount = 0;
        DiscoverySource activeSource = plan.sources().getFirst();
        try {
            for (DiscoverySource source : plan.sources()) {
                activeSource = source;
                discoveredCount = walkSource(plan.job(), plan.discoveryStage(), source, discoveredCount);
            }
            executionState.complete(plan.job(), plan.discoveryStage(), plan.sources(), discoveredCount,
                    System.currentTimeMillis());
        } catch (IOException | InvalidPathException | SecurityException exception) {
            String errorMessage = executionVersion == ScanExecutionDefinition.VERSION_2
                    ? safeDiscoveryError(activeSource.source())
                    : discoveryError(activeSource.source(), exception);
            executionState.fail(scanRunId, plan.job(), plan.discoveryStage(), plan.sources(), activeSource,
                    System.currentTimeMillis(), errorMessage);
            throw new DiscoveryExecutionFailedException(errorMessage, exception);
        } catch (RuntimeException exception) {
            if (executionVersion != ScanExecutionDefinition.VERSION_2) {
                throw exception;
            }
            String errorMessage = safeDiscoveryError(activeSource.source());
            executionState.fail(scanRunId, plan.job(), plan.discoveryStage(), plan.sources(), activeSource,
                    System.currentTimeMillis(), errorMessage);
            throw new Version2ExecutionFailedException(errorMessage, exception);
        }

        return findByScanRunIdAndVersion(scanRunId, executionVersion).orElseThrow();
    }

    private DiscoveryPlan preflight(long scanRunId, long executionVersion) {
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

        Job job = jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, executionVersion)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        if (!PENDING_STATUS.equals(scanRun.status())
                || !ScanExecutionDefinition.JOB_TYPE.equals(job.jobType())
                || job.executionVersion() != executionVersion
                || !PENDING_STATUS.equals(job.status())
                || !ScanExecutionDefinition.DISCOVERY.equals(job.currentStageType())) {
            throw new DiscoveryConflictException("Execution is not eligible to start DISCOVERY");
        }

        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.DISCOVERY)
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
        batchWriter.write(job.id(), discoveryStage.id(), job.executionVersion(),
                List.copyOf(batch), progressCompleted);
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

    private static String safeDiscoveryError(Source source) {
        return "Discovery failed for Source " + source.id();
    }

    private void requireScanRun(long scanRunId) {
        if (scanRepository.findScanRunById(scanRunId).isEmpty()) {
            throw new NoSuchElementException("ScanRun " + scanRunId + " does not exist");
        }
    }

    private record DiscoveryPlan(Job job, JobStage discoveryStage, List<DiscoverySource> sources) {
    }
}
