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

    private static final String SCAN_JOB_TYPE = "SCAN";
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String DISCOVERY_STAGE_TYPE = "DISCOVERY";
    private static final String RECONCILIATION_STAGE_TYPE = "RECONCILIATION";

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
        List<ScanRunSource> sources = preflight(scanRunId);
        long assignedCount = 0;
        long skippedCount = 0;

        for (ScanRunSource source : sources) {
            long afterFileEntryId = 0;
            while (true) {
                List<ContentAssignmentCandidate> candidates = catalogRepository.findContentAssignmentCandidates(
                        source.id(), source.completedGeneration(), afterFileEntryId, PAGE_SIZE);
                if (candidates.isEmpty()) {
                    break;
                }

                for (ContentAssignmentCandidate candidate : candidates) {
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

    private List<ScanRunSource> preflight(long scanRunId) {
        ScanRun scanRun = scanRepository.findScanRunById(scanRunId)
                .orElseThrow(() -> new NoSuchElementException("ScanRun " + scanRunId + " does not exist"));
        List<ScanRunSource> sources = scanRepository.findScanRunSourcesByScanRunId(scanRunId);
        Job job = jobRepository.findJobByScanRunIdAndType(scanRunId, SCAN_JOB_TYPE)
                .orElseThrow(() -> new NoSuchElementException(
                        "Execution handoff for ScanRun " + scanRunId + " does not exist"));
        JobStage discoveryStage = jobRepository.findJobStageByJobIdAndType(job.id(), DISCOVERY_STAGE_TYPE)
                .orElseThrow(() -> new ContentAssignmentConflictException("DISCOVERY stage does not exist"));
        JobStage reconciliationStage = jobRepository.findJobStageByJobIdAndType(
                job.id(), RECONCILIATION_STAGE_TYPE)
                .orElseThrow(() -> new ContentAssignmentConflictException("RECONCILIATION stage does not exist"));

        if (!COMPLETED_STATUS.equals(scanRun.status())
                || scanRun.startedAtMs() == null
                || scanRun.finishedAtMs() == null
                || !SCAN_JOB_TYPE.equals(job.jobType())
                || !COMPLETED_STATUS.equals(job.status())
                || job.currentStageType() != null
                || job.finishedAtMs() == null
                || !COMPLETED_STATUS.equals(discoveryStage.status())
                || !COMPLETED_STATUS.equals(reconciliationStage.status())) {
            throw new ContentAssignmentConflictException(
                    "ScanRun is not eligible for ContentRecord assignment");
        }

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

        return List.copyOf(sources);
    }
}
