package io.github.topher6835.mediacompare.scan;

import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Version2BackgroundIndexingService {
    private static final Logger log = LoggerFactory.getLogger(Version2BackgroundIndexingService.class);
    private final Version3ScanExecutionService executions;
    private final Version2IndexingExecutor executor;
    private final Version2InterruptionRecovery recovery;

    public Version2BackgroundIndexingService(Version3ScanExecutionService executions,
            Version2IndexingExecutor executor, Version2InterruptionRecovery recovery) {
        this.executions = executions;
        this.executor = executor;
        this.recovery = recovery;
    }

    /** Reject ambient transactions: create must commit before any submission occurs. */
    @Transactional(propagation = Propagation.NEVER)
    public ScanExecutionDetails start(long scanRunId) {
        ScanExecutionDetails accepted = executions.create(scanRunId);
        submitAccepted(accepted);
        return accepted;
    }

    @Transactional(propagation = Propagation.NEVER)
    public void submitAccepted(ScanExecutionDetails accepted) {
        try {
            executor.execute(() -> run(accepted.job().scanRunId(), accepted.job().id()));
        } catch (RejectedExecutionException exception) {
            recovery.failIfActive(accepted.job().id(), "Execution could not be scheduled");
            throw new Version2SchedulingException(exception);
        }
    }

    private void run(long scanRunId, long jobId) {
        try {
            executions.run(scanRunId);
        } catch (Exception exception) {
            log.warn("Version-3 indexing stopped: Job {}, ScanRun {}", jobId, scanRunId, exception);
            // Some JDBC implementations reject operations on an interrupted thread. Preserve the
            // signal, but let this short failure transaction run without that flag set.
            boolean interrupted = Thread.interrupted();
            try {
                recovery.failIfActive(jobId, interrupted ? "Execution interrupted"
                        : "Execution failed unexpectedly");
            } catch (RuntimeException finalizationFailure) {
                log.error("Could not finalize Job {}; startup recovery is required", jobId, finalizationFailure);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
