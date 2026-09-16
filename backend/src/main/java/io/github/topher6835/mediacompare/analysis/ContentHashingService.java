package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.springframework.stereotype.Service;

@Service
public class ContentHashingService {

    static final int PAGE_SIZE = 250;

    private static final String SCAN_JOB_TYPE = "SCAN";
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String DISCOVERY_STAGE_TYPE = "DISCOVERY";
    private static final String RECONCILIATION_STAGE_TYPE = "RECONCILIATION";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final CatalogRepository catalogRepository;
    private final AnalysisRepository analysisRepository;
    private final ContentHashFileHasher fileHasher;
    private final ContentHashWriter contentHashWriter;

    public ContentHashingService(ScanRepository scanRepository, JobRepository jobRepository,
            CatalogRepository catalogRepository, AnalysisRepository analysisRepository,
            ContentHashFileHasher fileHasher, ContentHashWriter contentHashWriter) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.catalogRepository = catalogRepository;
        this.analysisRepository = analysisRepository;
        this.fileHasher = fileHasher;
        this.contentHashWriter = contentHashWriter;
    }

    public ContentHashingResult hash(long scanRunId) {
        List<ScanRunSource> sources = preflight(scanRunId);
        long hashedCount = 0;
        long cachedCount = 0;
        long skippedCount = 0;
        long failedCount = 0;

        for (ScanRunSource scanRunSource : sources) {
            long afterFileEntryId = 0;
            while (true) {
                List<ContentHashCandidate> candidates = catalogRepository.findContentHashCandidates(
                        scanRunSource.id(), scanRunSource.completedGeneration(),
                        afterFileEntryId, PAGE_SIZE);
                if (candidates.isEmpty()) {
                    break;
                }

                for (ContentHashCandidate candidate : candidates) {
                    afterFileEntryId = candidate.fileEntryId();
                    CacheState cacheState = cacheState(candidate);
                    if (cacheState == CacheState.REUSABLE) {
                        cachedCount++;
                        continue;
                    }
                    if (cacheState == CacheState.BLOCKED) {
                        failedCount++;
                        continue;
                    }

                    Optional<Source> sourceResult = catalogRepository.findSourceById(candidate.sourceId());
                    if (sourceResult.isEmpty()) {
                        failedCount++;
                        continue;
                    }
                    Source source = sourceResult.orElseThrow();
                    if (source.locationRevision() != candidate.sourceLocationRevision()) {
                        skippedCount++;
                        continue;
                    }

                    long startedAtMs = System.currentTimeMillis();
                    try {
                        String digestHex = fileHasher.hash(
                                Path.of(source.rootPath()), candidate);
                        contentHashWriter.publish(
                                candidate, digestHex, startedAtMs, System.currentTimeMillis());
                        hashedCount++;
                    } catch (StaleContentHashException exception) {
                        skippedCount++;
                    } catch (IOException | InvalidPathException | SecurityException
                            | UnsupportedOperationException exception) {
                        failedCount++;
                    }
                }
            }
        }

        return new ContentHashingResult(
                scanRunId, hashedCount, cachedCount, skippedCount, failedCount);
    }

    private CacheState cacheState(ContentHashCandidate candidate) {
        Optional<AnalysisRecord> existing = analysisRepository.findAnalysisRecordByCacheKey(
                candidate.contentRecordId(),
                Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH);
        if (existing.isEmpty()) {
            return CacheState.NONE;
        }

        AnalysisRecord analysisRecord = existing.orElseThrow();
        if (!COMPLETED_STATUS.equals(analysisRecord.status())) {
            return CacheState.BLOCKED;
        }
        ContentHash contentHash = analysisRepository.findContentHash(analysisRecord.id())
                .orElseThrow(() -> new ContentHashIntegrityException(
                        analysisRecord.id(), "completed analysis has no ContentHash"));
        if (!Sha256AnalysisDefinition.ALGORITHM.equals(contentHash.algorithm())
                || !Sha256AnalysisDefinition.isValidDigest(contentHash.digestHex())) {
            throw new ContentHashIntegrityException(
                    analysisRecord.id(), "ContentHash algorithm or digest is invalid");
        }
        return CacheState.REUSABLE;
    }

    private List<ScanRunSource> preflight(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> sources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);
        Job job = jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(job.id(), DISCOVERY_STAGE_TYPE)
                .orElseThrow(() -> new ContentHashingConflictException("DISCOVERY stage does not exist"));
        JobStage reconciliationStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), RECONCILIATION_STAGE_TYPE)
                .orElseThrow(() -> new ContentHashingConflictException("RECONCILIATION stage does not exist"));

        if (!COMPLETED_STATUS.equals(scanRun.status())
                || scanRun.startedAtMs() == null
                || scanRun.finishedAtMs() == null
                || !SCAN_JOB_TYPE.equals(job.jobType())
                || !COMPLETED_STATUS.equals(job.status())
                || job.currentStageType() != null
                || job.finishedAtMs() == null
                || !COMPLETED_STATUS.equals(discoveryStage.status())
                || !COMPLETED_STATUS.equals(reconciliationStage.status())) {
            throw new ContentHashingConflictException("ScanRun is not eligible for content hashing");
        }

        for (ScanRunSource source : sources) {
            if (!COMPLETED_STATUS.equals(source.status())
                    || source.traversalGeneration() <= 0
                    || source.completedGeneration() == null
                    || source.completedGeneration() != source.traversalGeneration()
                    || source.completedAtMs() == null) {
                throw new ContentHashingConflictException(
                        "Source " + source.sourceId() + " is not eligible for content hashing");
            }
        }

        return List.copyOf(sources);
    }

    private enum CacheState {
        NONE,
        REUSABLE,
        BLOCKED
    }
}
