package io.github.topher6835.mediacompare.analysis;

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
public class MediaMetadataExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MediaMetadataExecutor.class);
    private final CatalogOwnership ownership;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            task -> {
                Thread thread = new Thread(task, "media-compare-metadata");
                thread.setDaemon(false);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public MediaMetadataExecutor(CatalogOwnership ownership, MediaMetadataStartup startup) {
        this.ownership = ownership;
    }

    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void destroy() {
        shutdown(Duration.ofSeconds(30));
    }

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
            log.warn("Metadata analysis exceeded shutdown budget; catalog ownership retained until process exit");
        }
    }
}
