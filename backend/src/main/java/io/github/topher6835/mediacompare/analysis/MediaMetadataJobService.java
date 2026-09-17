package io.github.topher6835.mediacompare.analysis;

import java.util.List;
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
                .orElseThrow(() -> new MediaMetadataJobConflictException(
                        "Media metadata Job " + jobId + " does not exist"));
        return new MediaMetadataExecutionDetails(job, jobs.findJobStagesByJobId(jobId));
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
