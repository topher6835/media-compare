package io.github.topher6835.mediacompare.preview;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Transient scheduling only: at most one running and 64 queued FileEntries, with no durable Job. */
@Component
public class ThumbnailScheduler implements DisposableBean {
    public enum Status { QUEUED, ALREADY_QUEUED, QUEUE_FULL }
    public record Result(long fileEntryId, Status status) {
    }

    private static final Logger log = LoggerFactory.getLogger(ThumbnailScheduler.class);
    private final CatalogOwnership ownership;
    private io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry exfat;

    @Autowired
    void exfatLifecycle(io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry exfat) { this.exfat = exfat; }
    private final LongFunction<ThumbnailGenerationResult> generate;
    private final Set<Long> queuedOrRunning = new HashSet<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "media-compare-thumbnails");
                thread.setDaemon(false);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public ThumbnailScheduler(SmallThumbnailService thumbnails, CatalogOwnership ownership) {
        this(ownership, thumbnails::generate);
    }

    // Narrow deterministic test seam; production always invokes the existing generator.
    ThumbnailScheduler(CatalogOwnership ownership, LongFunction<ThumbnailGenerationResult> generate) {
        this.ownership = ownership;
        this.generate = generate;
    }

    public synchronized Result schedule(long fileEntryId) {
        if (fileEntryId <= 0) {
            throw new IllegalArgumentException("FileEntry ID must be positive");
        }
        if (!queuedOrRunning.add(fileEntryId)) {
            return new Result(fileEntryId, Status.ALREADY_QUEUED);
        }
        try {
            executor.execute(new ThumbnailTask(fileEntryId));
            return new Result(fileEntryId, Status.QUEUED);
        } catch (RejectedExecutionException fullOrClosing) {
            queuedOrRunning.remove(fileEntryId);
            return new Result(fileEntryId, Status.QUEUE_FULL);
        }
    }

    private final class ThumbnailTask implements Runnable {
        private final long fileEntryId;

        ThumbnailTask(long fileEntryId) {
            this.fileEntryId = fileEntryId;
        }

        @Override
        public void run() {
            try {
                // Resolve current catalog/filesystem evidence when execution begins, never at admission.
                generate.apply(fileEntryId);
            } catch (RuntimeException failure) {
                // No arbitrary messages/paths/stack traces, retry loop, or persisted failure state.
                log.warn("Thumbnail generation failed for FileEntry {} ({})",
                        fileEntryId, failure.getClass().getSimpleName());
            } finally {
                synchronized (ThumbnailScheduler.this) {
                    queuedOrRunning.remove(fileEntryId);
                }
            }
        }
    }

    @Override
    public void destroy() {
        if (exfat != null) exfat.shutdown();
        shutdown(Duration.ofSeconds(30));
    }

    void shutdown(Duration budget) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(budget.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        var abandoned = executor.shutdownNow();
        synchronized (this) {
            for (Runnable task : abandoned) {
                queuedOrRunning.remove(((ThumbnailTask) task).fileEntryId);
            }
        }
        if (!executor.isTerminated()) {
            ownership.retainUntilProcessExit();
            log.warn("Thumbnails exceeded shutdown budget; catalog ownership retained until process exit");
        }
    }
}
