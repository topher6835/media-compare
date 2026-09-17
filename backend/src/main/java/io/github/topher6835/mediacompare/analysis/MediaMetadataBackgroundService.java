package io.github.topher6835.mediacompare.analysis;

import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaMetadataBackgroundService {

    private static final Logger log = LoggerFactory.getLogger(MediaMetadataBackgroundService.class);
    private final MediaMetadataJobService jobs;
    private final MediaMetadataExecutor executor;
    private final MediaMetadataInterruptionRecovery recovery;

    public MediaMetadataBackgroundService(
            MediaMetadataJobService jobs,
            MediaMetadataExecutor executor,
            MediaMetadataInterruptionRecovery recovery) {
        this.jobs = jobs;
        this.executor = executor;
        this.recovery = recovery;
    }

    /** Reject ambient transactions: Job creation must commit before submission. */
    @Transactional(propagation = Propagation.NEVER)
    public MediaMetadataExecutionDetails start() {
        MediaMetadataExecutionDetails accepted = jobs.create();
        try {
            executor.execute(() -> run(accepted.job().id()));
        } catch (RejectedExecutionException exception) {
            recovery.failIfActive(accepted.job().id(), "Execution could not be scheduled");
            throw new MediaMetadataSchedulingException(exception);
        }
        return accepted;
    }

    private void run(long jobId) {
        try {
            jobs.run(jobId);
        } catch (Exception exception) {
            log.warn("Media metadata execution stopped: Job {}", jobId, exception);
            boolean interrupted = Thread.interrupted();
            try {
                recovery.failIfActive(jobId, interrupted
                        ? "Execution interrupted"
                        : MediaMetadataJobService.JOB_FAILURE_MESSAGE);
            } catch (RuntimeException finalizationFailure) {
                log.error("Could not finalize media metadata Job {}; startup recovery is required",
                        jobId, finalizationFailure);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
