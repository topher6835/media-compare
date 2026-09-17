package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.Objects;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Version2InterruptionRecovery {
    public static final String RESTART_MESSAGE = "Execution interrupted by application restart";
    private static final Logger log = LoggerFactory.getLogger(Version2InterruptionRecovery.class);
    private static final List<String> STAGES = List.of("DISCOVERY", "RECONCILIATION",
            "CONTENT_ASSIGNMENT", "CONTENT_HASHING");
    private final JobRepository jobs;
    private final ScanRepository scans;
    private final ContentAssignmentStageResultCodec assignmentCodec;

    public Version2InterruptionRecovery(JobRepository jobs, ScanRepository scans,
            ContentAssignmentStageResultCodec assignmentCodec) {
        this.jobs = jobs;
        this.scans = scans;
        this.assignmentCodec = assignmentCodec;
    }

    /** Caller owns the catalog and has stopped this execution; never race a live worker. */
    @Transactional
    public void failIfActive(long jobId, String message) {
        Job job = jobs.findJobById(jobId).orElseThrow();
        if (job.executionVersion() != 2 || !"SCAN".equals(job.jobType())
                || List.of("COMPLETED", "FAILED").contains(job.status())) {
            return;
        }
        try {
            validateAndFail(job, message);
        } catch (RuntimeException exception) {
            log.error("Cannot finalize version-2 Job {} / ScanRun {} / stage {}",
                    job.id(), job.scanRunId(), job.currentStageType(), exception);
            throw new IllegalStateException("Invalid interrupted execution: Job " + job.id()
                    + ", ScanRun " + job.scanRunId() + ", stage " + job.currentStageType(), exception);
        }
    }

    private void validateAndFail(Job job, String message) {
        require(job.scanRunId() != null, "missing ScanRun");
        ScanRun scan = scans.findScanRunById(job.scanRunId()).orElseThrow();
        boolean pending = "PENDING".equals(job.status());
        int currentIndex = job.currentStageType() == null ? -1 : STAGES.indexOf(job.currentStageType());
        require((pending || "RUNNING".equals(job.status())) && currentIndex >= 0
                && job.finishedAtMs() == null && job.errorMessage() == null, "invalid active Job");
        require(pending ? currentIndex == 0 && job.startedAtMs() == null && job.attemptCount() == 0
                : job.startedAtMs() != null && job.attemptCount() == 1, "invalid Job claim");
        require("INDEX".equals(scan.requestType()), "wrong ScanRun type");
        boolean scanActive = List.of("PENDING", "RUNNING").contains(scan.status());
        require(scanActive ? scan.status().equals(job.status()) && scan.finishedAtMs() == null
                && scan.errorMessage() == null && (pending == (scan.startedAtMs() == null))
                : List.of("COMPLETED", "FAILED").contains(scan.status()) && scan.finishedAtMs() != null,
                "invalid ScanRun state");
        jobs.findJobByScanRunIdAndTypeAndExecutionVersion(scan.id(), "SCAN", 1).ifPresent(legacy -> {
            require(!"RUNNING".equals(legacy.status()), "ScanRun also owned by running version-1 Job");
        });

        List<JobStage> stages = jobs.findJobStagesByJobId(job.id());
        JobStage current = stages.stream().filter(stage -> stage.stageType().equals(job.currentStageType()))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing current stage"));
        for (int index = 0; index < currentIndex; index++) {
            String type = STAGES.get(index);
            require(stages.stream().anyMatch(stage -> stage.stageType().equals(type)
                    && "COMPLETED".equals(stage.status())), "missing completed predecessor " + type);
        }
        for (JobStage stage : stages) {
            int index = STAGES.indexOf(stage.stageType());
            require(index >= 0, "unknown stage " + stage.id());
            String expected = index < currentIndex ? "COMPLETED"
                    : index > currentIndex || pending ? "PENDING" : stage.status();
            require(stage.status().equals(expected), "inconsistent stage " + stage.id());
            require(stage.errorMessage() == null, "unexpected stage error " + stage.id());
            if ("PENDING".equals(stage.status())) {
                require(stage.startedAtMs() == null && stage.finishedAtMs() == null
                        && stage.attemptCount() == 0 && stage.resultJson() == null
                        && stage.progressCompleted() == 0 && stage.progressTotal() == null,
                        "invalid pending stage " + stage.id());
                require(index != 0 || pending, "running Job with unclaimed discovery");
            } else {
                require(List.of("RUNNING", "COMPLETED").contains(stage.status())
                        && stage.startedAtMs() != null && stage.attemptCount() == 1,
                        "invalid stage claim " + stage.id());
                require("COMPLETED".equals(stage.status()) == (stage.finishedAtMs() != null),
                        "invalid stage finish " + stage.id());
                require(index < currentIndex || "RUNNING".equals(stage.status()), "invalid current stage");
                if (index == 2 && "COMPLETED".equals(stage.status())) {
                    assignmentCodec.read(stage.resultJson());
                } else {
                    require(stage.resultJson() == null, "unexpected result on stage " + stage.id());
                }
            }
        }

        List<ScanRunSource> sources = scans.findScanRunSourcesByScanRunId(scan.id());
        require(!sources.isEmpty(), "no Sources");
        for (ScanRunSource source : sources) {
            validateSource(source, pending, currentIndex, current);
        }

        long now = System.currentTimeMillis();
        for (ScanRunSource source : sources) {
            if ("DISCOVERING".equals(source.status())) {
                require(scans.failSourceDiscovery(source.id(), now, message) == 1, "Source changed");
            }
        }
        if ("RUNNING".equals(current.status())) {
            require(jobs.failVersion2Stage(job.id(), current.id(), current.stageType(), now, message) == 1,
                    "stage changed");
        }
        if (scanActive) {
            require(scans.failNonterminalScanRun(scan.id(), now, message) == 1, "ScanRun changed");
        }
        require(jobs.failActiveVersion2Job(job.id(), now, message) == 1, "Job changed");
    }

    private static void validateSource(ScanRunSource source, boolean pending, int currentIndex, JobStage current) {
        String expected = pending ? "PENDING" : currentIndex == 0 ? "DISCOVERING"
                : currentIndex == 1 ? "DISCOVERED" : "COMPLETED";
        boolean reconciled = "COMPLETED".equals(source.status());
        require(source.status().equals(expected) || (currentIndex == 1
                && "RUNNING".equals(current.status()) && reconciled), "invalid Source " + source.id());
        require(source.errorMessage() == null, "Source error " + source.id());
        require(pending ? source.traversalGeneration() == 0 && source.startedAtMs() == null
                : source.traversalGeneration() > 0 && source.startedAtMs() != null,
                "invalid traversal " + source.id());
        require(reconciled ? Objects.equals(source.completedGeneration(), source.traversalGeneration())
                && source.completedAtMs() != null
                : source.completedGeneration() == null && source.completedAtMs() == null,
                "invalid completion evidence " + source.id());
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalStateException(detail);
        }
    }
}
