package io.github.topher6835.mediacompare.analysis;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.IndexingInterruptedException;

import org.springframework.stereotype.Service;

@Service
public class MediaMetadataJobService {

    static final String CONTENT_FAILURE_MESSAGE = "Image metadata extraction failed";
    static final String JOB_FAILURE_MESSAGE = "Image metadata processing failed";

    private final JobRepository jobs;
    private final MediaMetadataJobAcceptance acceptance;
    private final MediaMetadataCandidateRepository candidates;
    private final ImageIoMediaMetadataAnalyzer analyzer;
    private final MediaMetadataPublisher publisher;
    private final MediaMetadataCache cache;
    private final ImageMetadataStageResultCodec resultCodec;
    private final MediaMetadataExecutionState executionState;

    public MediaMetadataJobService(
            JobRepository jobs,
            MediaMetadataJobAcceptance acceptance,
            MediaMetadataCandidateRepository candidates,
            ImageIoMediaMetadataAnalyzer analyzer,
            MediaMetadataPublisher publisher,
            MediaMetadataCache cache,
            ImageMetadataStageResultCodec resultCodec,
            MediaMetadataExecutionState executionState) {
        this.jobs = jobs;
        this.acceptance = acceptance;
        this.candidates = candidates;
        this.analyzer = analyzer;
        this.publisher = publisher;
        this.cache = cache;
        this.resultCodec = resultCodec;
        this.executionState = executionState;
    }

    public MediaMetadataExecutionDetails create() {
        return acceptance.create();
    }

    public MediaMetadataExecutionDetails run(long jobId) {
        Job job = jobs.findJobById(jobId)
                .orElseThrow(() -> new MediaMetadataJobConflictException(
                        "Media metadata Job " + jobId + " does not exist"));
        JobStage stage = jobs.findJobStageByJobIdAndType(
                jobId, MediaMetadataJobDefinition.IMAGE_METADATA_STAGE)
                .orElseThrow(() -> new MediaMetadataJobConflictException(
                        "IMAGE_METADATA stage does not exist"));
        requirePending(job, stage);
        executionState.start(job, stage, System.currentTimeMillis());

        try {
            ImageMetadataStageResult result = processCandidates(job, stage);
            executionState.complete(job, stage, resultCodec.write(result),
                    result.candidatesAttempted(), System.currentTimeMillis());
            return find(jobId);
        } catch (RuntimeException exception) {
            IndexingInterruptedException.propagateIfInterrupted(exception);
            executionState.fail(
                    job, stage, System.currentTimeMillis(), JOB_FAILURE_MESSAGE);
            throw exception;
        }
    }

    public MediaMetadataExecutionDetails find(long jobId) {
        Job job = jobs.findJobById(jobId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Media metadata Job " + jobId + " does not exist"));
        if (job.scanRunId() != null
                || !MediaMetadataJobDefinition.JOB_TYPE.equals(job.jobType())
                || job.executionVersion() != MediaMetadataJobDefinition.EXECUTION_VERSION) {
            throw new java.util.NoSuchElementException("Job is not a metadata execution");
        }
        List<JobStage> stages = jobs.findJobStagesByJobId(jobId);
        if (stages.size() != 1
                || !MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(stages.getFirst().stageType())) {
            throw new IllegalStateException("Invalid metadata Job stage shape");
        }
        JobStage stage = stages.getFirst();
        validateDurableState(job, stage);
        return new MediaMetadataExecutionDetails(job, stages);
    }

    private void validateDurableState(Job job, JobStage stage) {
        if (!List.of("PENDING", "RUNNING", "COMPLETED", "FAILED").contains(job.status())) {
            throw new IllegalStateException("Invalid metadata Job status");
        }
        if (!List.of("PENDING", "RUNNING", "COMPLETED", "FAILED").contains(stage.status())) {
            throw new IllegalStateException("Invalid metadata stage status");
        }
        if (!job.status().equals(stage.status())) {
            throw new IllegalStateException("Metadata Job/stage status mismatch");
        }
        switch (job.status()) {
        case "PENDING" -> {
            require(job.startedAtMs() == null && job.finishedAtMs() == null
                    && MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(job.currentStageType())
                    && stage.startedAtMs() == null && stage.finishedAtMs() == null
                    && stage.resultJson() == null, "Invalid pending metadata lifecycle");
        }
        case "RUNNING" -> {
            require(job.startedAtMs() != null && job.finishedAtMs() == null
                    && MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(job.currentStageType())
                    && stage.startedAtMs() != null && stage.finishedAtMs() == null
                    && stage.resultJson() == null, "Invalid running metadata lifecycle");
        }
        case "COMPLETED" -> {
            require(job.startedAtMs() != null && job.finishedAtMs() != null
                    && job.currentStageType() == null && stage.startedAtMs() != null
                    && stage.finishedAtMs() != null && stage.resultJson() != null,
                    "Invalid completed metadata lifecycle");
            ImageMetadataStageResult result = resultCodec.read(stage.resultJson());
            if (result.candidatesAttempted() != stage.progressCompleted()
                    || result.candidatesAttempted() != job.progressCompleted()
                    || !java.util.Objects.equals(stage.progressTotal(), stage.progressCompleted())
                    || !java.util.Objects.equals(job.progressTotal(), job.progressCompleted())) {
                throw new IllegalStateException("Metadata stage result disagrees with durable progress");
            }
        }
        case "FAILED" -> require(job.finishedAtMs() != null && job.currentStageType() == null
                && stage.finishedAtMs() != null && stage.resultJson() == null,
                "Invalid failed metadata lifecycle");
        default -> throw new IllegalStateException("Invalid metadata Job status");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

    private ImageMetadataStageResult processCandidates(Job job, JobStage stage) {
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        long afterContentRecordId = 0;
        MutableCounts counts = new MutableCounts();
        while (true) {
            IndexingInterruptedException.check();
            List<MediaMetadataContentCandidate> page = candidates.findCandidates(
                    definition, afterContentRecordId, MediaMetadataJobDefinition.CANDIDATE_BATCH_SIZE);
            if (page.isEmpty()) {
                return counts.toResult();
            }
            for (MediaMetadataContentCandidate candidate : page) {
                IndexingInterruptedException.check();
                afterContentRecordId = candidate.contentRecordId();
                counts.candidatesAttempted++;
                processCandidate(candidate, definition, counts);
            }
            executionState.updateProgress(job, stage, counts.candidatesAttempted);
        }
    }

    private void processCandidate(
            MediaMetadataContentCandidate candidate,
            MediaMetadataAnalysisDefinition definition,
            MutableCounts counts) {
        try {
            Optional<MediaMetadataResult> result = analyzer.analyze(candidate);
            if (result.isEmpty()) {
                counts.staleOrUnavailable++;
            } else {
                counts.recordCompleted(result.orElseThrow());
            }
        } catch (CurrentImageMetadataExtractionException exception) {
            try {
                AnalysisRecord stored = publisher.publishFailureIfStillCurrent(
                        exception.candidate(), definition, exception.startedAtMs(),
                        System.currentTimeMillis(), CONTENT_FAILURE_MESSAGE);
                if ("COMPLETED".equals(stored.status())) {
                    counts.recordCompleted(cache.findReusableResult(
                            candidate.contentRecordId(), definition).orElseThrow());
                } else {
                    counts.failed++;
                }
            } catch (StaleMediaMetadataEvidenceException stale) {
                counts.staleOrUnavailable++;
            }
        }
    }

    private static void requirePending(Job job, JobStage stage) {
        if (job.scanRunId() != null
                || !MediaMetadataJobDefinition.JOB_TYPE.equals(job.jobType())
                || job.executionVersion() != MediaMetadataJobDefinition.EXECUTION_VERSION
                || !"PENDING".equals(job.status())
                || !MediaMetadataJobDefinition.IMAGE_METADATA_STAGE.equals(job.currentStageType())
                || !"PENDING".equals(stage.status())) {
            throw new MediaMetadataJobConflictException("Media metadata Job is not eligible to run");
        }
    }

    private static final class MutableCounts {
        private long candidatesAttempted;
        private long completedAvailable;
        private long completedUnsupported;
        private long failed;
        private long staleOrUnavailable;

        private void recordCompleted(MediaMetadataResult result) {
            if (result.outcome() == MediaMetadataOutcome.AVAILABLE) {
                completedAvailable++;
            } else {
                completedUnsupported++;
            }
        }

        private ImageMetadataStageResult toResult() {
            return new ImageMetadataStageResult(
                    ImageMetadataStageResult.CURRENT_VERSION,
                    candidatesAttempted,
                    completedAvailable,
                    completedUnsupported,
                    failed,
                    staleOrUnavailable);
        }
    }
}
