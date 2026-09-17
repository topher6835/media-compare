package io.github.topher6835.mediacompare.scan;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.topher6835.mediacompare.config.CatalogOwnership;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

@Component
public class Version2IndexingExecutor implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(Version2IndexingExecutor.class);
    private final CatalogOwnership ownership;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "media-compare-indexing");
                thread.setDaemon(false);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public Version2IndexingExecutor(CatalogOwnership ownership, Version2IndexingStartup startup) {
        this.ownership = ownership;
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void destroy() {
        shutdown(Duration.ofSeconds(30));
    }

    // A shorter budget makes shutdown tests deterministic without waiting thirty seconds.
    void shutdown(Duration budget) {
        executor.shutdown();
        try {
            if (executor.awaitTermination(budget.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        if (!executor.isTerminated()) {
            ownership.retainUntilProcessExit();
            log.warn("Indexing exceeded shutdown budget; catalog ownership retained until process exit");
        }
    }
}
