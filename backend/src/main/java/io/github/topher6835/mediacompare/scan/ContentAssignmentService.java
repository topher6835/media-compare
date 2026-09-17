package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentCandidate;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentWriter;
import io.github.topher6835.mediacompare.catalog.StaleContentAssignmentException;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.springframework.stereotype.Service;

@Service
public class ContentAssignmentService {

    static final int PAGE_SIZE = 250;

    private static final String COMPLETED_STATUS = "COMPLETED";

    private final ScanRepository scanRepository;
    private final JobRepository jobRepository;
    private final CatalogRepository catalogRepository;
    private final ContentAssignmentWriter contentAssignmentWriter;

    public ContentAssignmentService(ScanRepository scanRepository, JobRepository jobRepository,
            CatalogRepository catalogRepository, ContentAssignmentWriter contentAssignmentWriter) {
        this.scanRepository = scanRepository;
        this.jobRepository = jobRepository;
        this.catalogRepository = catalogRepository;
        this.contentAssignmentWriter = contentAssignmentWriter;
    }

    public ContentAssignmentResult assign(long scanRunId) {
        List<ScanRunSource> sources = preflightVersion1(scanRunId);
        return assignSources(scanRunId, sources);
    }

    ContentAssignmentResult assignSources(long scanRunId, List<ScanRunSource> sources) {
        long assignedCount = 0;
        long skippedCount = 0;

        for (ScanRunSource source : sources) {
            long afterFileEntryId = 0;
            while (true) {
                IndexingInterruptedException.check();
                List<ContentAssignmentCandidate> candidates = catalogRepository.findContentAssignmentCandidates(
                        source.id(), source.completedGeneration(), afterFileEntryId, PAGE_SIZE);
                if (candidates.isEmpty()) {
                    break;
                }

                for (ContentAssignmentCandidate candidate : candidates) {
                    IndexingInterruptedException.check();
                    afterFileEntryId = candidate.fileEntryId();
                    try {
                        contentAssignmentWriter.assign(candidate, System.currentTimeMillis());
                        assignedCount++;
                    } catch (StaleContentAssignmentException exception) {
                        skippedCount++;
                    }
                }
            }
        }

        return new ContentAssignmentResult(scanRunId, assignedCount, skippedCount);
    }

    List<ScanRunSource> requireCompletedSources(long scanRunId) {
        scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> sources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);
        requireCompletedSourceEvidence(sources);
        return List.copyOf(sources);
    }

    private List<ScanRunSource> preflightVersion1(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> sources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);
        Job job = jobRepository.findJobByScanRunIdAndTypeAndExecutionVersion(
                scanRunId, ScanExecutionDefinition.JOB_TYPE, ScanExecutionDefinition.VERSION_1)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.DISCOVERY)
                .orElseThrow(() -> new ContentAssignmentConflictException("DISCOVERY stage does not exist"));
        JobStage reconciliationStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), ScanExecutionDefinition.RECONCILIATION)
                .orElseThrow(() -> new ContentAssignmentConflictException("RECONCILIATION stage does not exist"));

        if (!COMPLETED_STATUS.equals(scanRun.status())
                || scanRun.startedAtMs() == null
                || scanRun.finishedAtMs() == null
                || !ScanExecutionDefinition.JOB_TYPE.equals(job.jobType())
                || job.executionVersion() != ScanExecutionDefinition.VERSION_1
                || !COMPLETED_STATUS.equals(job.status())
                || job.currentStageType() != null
                || job.finishedAtMs() == null
                || !COMPLETED_STATUS.equals(discoveryStage.status())
                || !COMPLETED_STATUS.equals(reconciliationStage.status())) {
            throw new ContentAssignmentConflictException(
                    "ScanRun is not eligible for ContentRecord assignment");
        }

        requireCompletedSourceEvidence(sources);
        return List.copyOf(sources);
    }

    private static void requireCompletedSourceEvidence(List<ScanRunSource> sources) {
        for (ScanRunSource source : sources) {
            if (!COMPLETED_STATUS.equals(source.status())
                    || source.traversalGeneration() <= 0
                    || source.completedGeneration() == null
                    || source.completedGeneration() != source.traversalGeneration()
                    || source.completedAtMs() == null) {
                throw new ContentAssignmentConflictException(
                        "Source " + source.sourceId() + " is not eligible for ContentRecord assignment");
            }
        }

    }
}
