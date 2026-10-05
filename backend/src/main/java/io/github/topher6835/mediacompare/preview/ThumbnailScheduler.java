package io.github.topher6835.mediacompare.preview;

import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataJobConflictException;
import io.github.topher6835.mediacompare.catalog.PersistedPhysicalActions;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadBundles;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadCapture;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadCatalog;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;
import io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest;
import io.github.topher6835.mediacompare.web.ThumbnailScheduleRequest;

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
    public enum Status { QUEUED, ALREADY_QUEUED, QUEUE_FULL, CACHED, AUTHORITY_UNAVAILABLE }
    public record Result(long fileEntryId, Status status) {
    }

    private static final Logger log = LoggerFactory.getLogger(ThumbnailScheduler.class);
    private final CatalogOwnership ownership;
    private ExfatAuthorityWindowRegistry exfat;

    @Autowired
    void exfatLifecycle(ExfatAuthorityWindowRegistry exfat) { this.exfat = exfat; }
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

    private PersistedPhysicalActions physicalActions;
    private ExfatContentReadBundles contentBundles;
    private ExfatContentReadCatalog contentCatalog;
    private MediaMetadataCandidateRepository candidates;
    private ExfatThumbnailService exfatThumbnails;
    @Autowired
    void contentReads(PersistedPhysicalActions actions,
            ExfatContentReadBundles bundles,
            ExfatContentReadCatalog catalog,
            MediaMetadataCandidateRepository candidates,
            ExfatThumbnailService thumbnails) {
        physicalActions = actions; contentBundles = bundles; contentCatalog = catalog;
        this.candidates = candidates; exfatThumbnails = thumbnails;
    }

    public java.util.List<Result> schedule(java.util.List<Long> ids,
            java.util.List<SourceAuthorityWindowRequest> windows) {
        // Reuse existing request validation even for internal callers.
        var request = new ThumbnailScheduleRequest(ids, windows);
        ids = request.fileEntryIds();
        windows = request.authorityWindows();
        var results = new java.util.LinkedHashMap<Long, Result>();
        var pending = new java.util.ArrayList<Long>();
        for (long id : ids) {
            if (!physicalActions.isExfat(id)) results.put(id, schedule(id));
            else if (exfatThumbnails.cached(id).isPresent()) results.put(id, new Result(id, Status.CACHED));
            else pending.add(id);
        }
        if (pending.isEmpty()) return ids.stream().map(results::get).toList();
        ExfatContentReadBundles.Batch batch;
        try { batch = contentBundles.admitBatch(windows); }
        catch (MediaMetadataJobConflictException | IllegalStateException unavailable) {
            for (long id : pending) results.put(id, new Result(id, Status.AUTHORITY_UNAVAILABLE));
            return ids.stream().map(results::get).toList();
        }
        try {
            var captured = new java.util.ArrayList<ThumbnailTask>();
            // Every capture precedes task submission; no native handle crosses this boundary.
            for (long id : pending) {
                var item = batch.admitItem();
                try {
                    var route = candidates.findExfatOccurrence(id,
                            batch.owners().stream().map(a -> a.window().sourceId()).toList()).orElseThrow();
                    var owner = batch.owners().stream().filter(a -> a.window().sourceId() == route.sourceId()).findFirst().orElseThrow();
                    captured.add(new ThumbnailTask(id, contentCatalog.capture(owner, id, route.membershipId()), item));
                } catch (RuntimeException unavailable) {
                    item.close(); results.put(id, new Result(id, Status.AUTHORITY_UNAVAILABLE));
                }
            }
            for (var task : captured) results.put(task.fileEntryId, submitCaptured(task));
        } finally { batch.seal(); }
        return ids.stream().map(results::get).toList();
    }

    private synchronized Result submitCaptured(ThumbnailTask task) {
        if (!queuedOrRunning.add(task.fileEntryId)) {
            task.neverStarted(); return new Result(task.fileEntryId, Status.AUTHORITY_UNAVAILABLE);
        }
        try { executor.execute(task); return new Result(task.fileEntryId, Status.QUEUED); }
        catch (RejectedExecutionException rejected) {
            queuedOrRunning.remove(task.fileEntryId); task.neverStarted(); return new Result(task.fileEntryId, Status.QUEUE_FULL);
        }
    }

    public synchronized Result schedule(long fileEntryId) {
        if (fileEntryId <= 0) {
            throw new IllegalArgumentException("FileEntry ID must be positive");
        }
        if (physicalActions != null && physicalActions.isExfat(fileEntryId)) {
            return new Result(fileEntryId, exfatThumbnails.cached(fileEntryId).isPresent() ? Status.CACHED : Status.AUTHORITY_UNAVAILABLE);
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
        private final ExfatContentReadCapture capture;
        private final ExfatContentReadBundles.Item item;

        ThumbnailTask(long fileEntryId) { this(fileEntryId, null, null); }
        ThumbnailTask(long fileEntryId, ExfatContentReadCapture capture,
                ExfatContentReadBundles.Item item) {
            this.fileEntryId = fileEntryId; this.capture = capture; this.item = item;
        }
        void neverStarted() { if (item != null) item.close(); }

        @Override
        public void run() {
            try {
                // Resolve current catalog/filesystem evidence when execution begins, never at admission.
                if (capture == null) generate.apply(fileEntryId); else exfatThumbnails.generate(capture);
            } catch (RuntimeException failure) {
                // No arbitrary messages/paths/stack traces, retry loop, or persisted failure state.
                log.warn("Thumbnail generation failed for FileEntry {} ({})",
                        fileEntryId, failure.getClass().getSimpleName());
            } finally {
                synchronized (ThumbnailScheduler.this) {
                    queuedOrRunning.remove(fileEntryId);
                }
                if (item != null) item.close();
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
                ThumbnailTask neverStarted = (ThumbnailTask) task;
                queuedOrRunning.remove(neverStarted.fileEntryId);
                neverStarted.neverStarted();
            }
        }
        if (!executor.isTerminated()) {
            ownership.retainUntilProcessExit();
            log.warn("Thumbnails exceeded shutdown budget; catalog ownership retained until process exit");
        }
    }
}
