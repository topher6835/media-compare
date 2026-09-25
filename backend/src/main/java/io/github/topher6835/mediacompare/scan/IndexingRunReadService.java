package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.NoSuchElementException;
import io.github.topher6835.mediacompare.analysis.ContentHashingStageResult;
import io.github.topher6835.mediacompare.analysis.ContentHashingStageResultCodec;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingRunReadService {
    private static final Logger log = LoggerFactory.getLogger(IndexingRunReadService.class);
    private static final List<String> STAGE_ORDER = List.of("DISCOVERY", "RECONCILIATION", "CONTENT_ASSIGNMENT", "CONTENT_HASHING");
    private final ScanRepository scans;
    private final JobRepository jobs;
    private final IndexingRunRepository summaries;
    private final ContentAssignmentStageResultCodec assignmentCodec;
    private final ContentHashingStageResultCodec hashingCodec;

    public IndexingRunReadService(ScanRepository scans, JobRepository jobs, IndexingRunRepository summaries,
            ContentAssignmentStageResultCodec assignmentCodec, ContentHashingStageResultCodec hashingCodec) {
        this.scans = scans;
        this.jobs = jobs;
        this.summaries = summaries;
        this.assignmentCodec = assignmentCodec;
        this.hashingCodec = hashingCodec;
    }

    @Transactional(readOnly = true)
    public IndexingRunDetails require(long scanRunId) {
        ScanRun scan = scans.findScanRunById(scanRunId).orElseThrow();
        Job job = jobs.findJobByScanRunIdAndTypeAndExecutionVersion(scanRunId, "SCAN", 3)
                .or(() -> jobs.findJobByScanRunIdAndTypeAndExecutionVersion(scanRunId, "SCAN", 2))
                .orElseThrow();
        if (!"INDEX".equals(scan.requestType())) throw new NoSuchElementException();
        try {
            requireValid(scan.status().equals(job.status()), "ScanRun/Job status mismatch");
            List<JobStage> stages = jobs.findJobStagesByJobId(job.id()).stream()
                    .sorted(java.util.Comparator.comparingInt(stage -> STAGE_ORDER.indexOf(stage.stageType()))).toList();
            requireValid(!stages.isEmpty() && stages.stream().allMatch(stage -> STAGE_ORDER.contains(stage.stageType())), "Invalid stages");
            validateStatus(job.status(), job.currentStageType(), job.finishedAtMs());
            if (job.currentStageType() != null) {
                requireValid(stages.stream().anyMatch(stage -> stage.stageType().equals(job.currentStageType())
                        && List.of("PENDING", "RUNNING").contains(stage.status())), "Missing active stage");
            }
            JobStage assignment = stage(stages, "CONTENT_ASSIGNMENT");
            JobStage hashing = stage(stages, "CONTENT_HASHING");
            var assigned = assignmentResult(assignment == null ? null : assignment.status(), assignment == null ? null : assignment.resultJson());
            var hashed = hashingResult(hashing == null ? null : hashing.status(), hashing == null ? null : hashing.resultJson());
            requireValid(!"COMPLETED".equals(job.status()) || (assigned != null && hashed != null
                    && stages.size() == 4 && stages.stream().allMatch(s -> "COMPLETED".equals(s.status()))), "Incomplete completed execution");
            List<Long> sourceIds = scans.findScanRunSourcesByScanRunId(scanRunId).stream().map(ScanRunSource::sourceId).toList();
            requireValid(!sourceIds.isEmpty(), "Missing Source membership");
            return new IndexingRunDetails(scan, job, sourceIds, stages, assigned, hashed);
        } catch (RuntimeException exception) {
            log.error("Invalid indexing read: ScanRun {}, Job {}", scanRunId, job.id(), exception);
            throw new IllegalStateException("Invalid durable indexing state", exception);
        }
    }

    @Transactional(readOnly = true)
    public SourceStatus sourceStatus() {
        Summary active = summaries.findActive().map(this::summary).orElse(null);
        List<SourceLatest> sources = summaries.findLatestBySource().stream()
                .map(row -> new SourceLatest(row.sourceId(), row.latest() == null ? null : summary(row.latest()))).toList();
        return new SourceStatus(active, sources);
    }

    private Summary summary(IndexingRunRepository.SummaryRow row) {
        try {
            validateStatus(row.status(), row.currentStage(), row.finishedAtMs());
            var assigned = assignmentResult(row.assignmentStatus(), row.assignmentJson());
            var hashed = hashingResult(row.hashingStatus(), row.hashingJson());
            requireValid(!"COMPLETED".equals(row.status()) || (assigned != null && hashed != null), "Missing completed results");
            boolean issues = "COMPLETED".equals(row.status()) && hashed != null
                    && (hashed.skippedCount() > 0 || hashed.failedCount() > 0);
            return new Summary(row.scanRunId(), row.jobId(), row.status(), row.currentStage(), row.progressCompleted(),
                    row.progressTotal(), row.createdAtMs(), row.startedAtMs(), row.finishedAtMs(), row.errorMessage(), issues);
        } catch (RuntimeException exception) {
            log.error("Invalid indexing summary: ScanRun {}, Job {}", row.scanRunId(), row.jobId(), exception);
            throw new IllegalStateException("Invalid durable indexing state", exception);
        }
    }

    private ContentAssignmentStageResult assignmentResult(String status, String json) {
        if ("COMPLETED".equals(status)) return assignmentCodec.read(json);
        requireValid(json == null, "Assignment result on unfinished stage");
        return null;
    }

    private ContentHashingStageResult hashingResult(String status, String json) {
        if ("COMPLETED".equals(status)) return hashingCodec.read(json);
        requireValid(json == null, "Hashing result on unfinished stage");
        return null;
    }

    private static JobStage stage(List<JobStage> stages, String type) {
        return stages.stream().filter(stage -> stage.stageType().equals(type)).findFirst().orElse(null);
    }

    private static void validateStatus(String status, String current, Long finished) {
        requireValid(List.of("PENDING", "RUNNING", "COMPLETED", "FAILED").contains(status), "Invalid Job status");
        boolean active = "PENDING".equals(status) || "RUNNING".equals(status);
        requireValid(active ? current != null && STAGE_ORDER.contains(current) && finished == null
                : current == null && finished != null, "Invalid Job boundary");
    }

    private static void requireValid(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record Summary(long scanRunId, long jobId, String status, String currentStage,
            long progressCompleted, Long progressTotal, long createdAtMs, Long startedAtMs,
            Long finishedAtMs, String errorMessage, boolean completedWithIssues) {}
    public record SourceLatest(long sourceId, Summary latest) {}
    public record SourceStatus(Summary active, List<SourceLatest> sources) {}
}
