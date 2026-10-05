package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.contentread.ExfatContentReadAuthority;
import io.github.topher6835.mediacompare.web.CreateMediaMetadataRunRequest;

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
            executor.execute(new Task(accepted.job().id(), java.util.List.of()));
        } catch (RejectedExecutionException exception) {
            try { recovery.failIfActive(accepted.job().id(), "Execution could not be scheduled"); }
            finally { jobs.finishContent(accepted.job().id()); }
            throw new MediaMetadataSchedulingException(exception);
        }
        return accepted;
    }

    @Transactional(propagation = Propagation.NEVER)
    public MediaMetadataExecutionDetails start(CreateMediaMetadataRunRequest request) {
        var admission = jobs.create(request);
        if (!admission.submit()) return admission.details();
        var accepted = admission.details();
        Task task = new Task(accepted.job().id(), jobs.contentOwners(accepted.job().id()));
        try { executor.execute(task); }
        catch (RejectedExecutionException failure) {
            task.neverStarted(); throw new MediaMetadataSchedulingException(failure);
        }
        return accepted;
    }

    private final class Task implements MediaMetadataExecutor.NeverStartedTask {
        private final long jobId;
        private final java.util.List<ExfatContentReadAuthority> owners;
        Task(long jobId, java.util.List<ExfatContentReadAuthority> owners) {
            this.jobId = jobId; this.owners = java.util.List.copyOf(owners);
        }
        @Override public void run() { runCaptured(jobId, owners); }
        @Override public void neverStarted() {
            try { recovery.failIfActive(jobId, "Execution could not be scheduled"); }
            finally { jobs.finishContent(jobId); }
        }
    }

    private void runCaptured(long jobId,
            java.util.List<ExfatContentReadAuthority> owners) {
        try {
            if (owners.isEmpty()) jobs.run(jobId); else jobs.run(jobId, owners);
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
