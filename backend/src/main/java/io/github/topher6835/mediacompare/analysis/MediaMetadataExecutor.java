package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;

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
    public interface NeverStartedTask extends Runnable { void neverStarted(); }


    private static final Logger log = LoggerFactory.getLogger(MediaMetadataExecutor.class);
    private final CatalogOwnership ownership;
    private ExfatAuthorityWindowRegistry exfat;

    @org.springframework.beans.factory.annotation.Autowired
    void exfatLifecycle(ExfatAuthorityWindowRegistry exfat) { this.exfat = exfat; }
    private final ThreadPoolExecutor executor;

    @org.springframework.beans.factory.annotation.Autowired
    public MediaMetadataExecutor(CatalogOwnership ownership, MediaMetadataStartup startup) {
        this(ownership, startup, new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                task -> {
                    Thread thread = new Thread(task, "media-compare-metadata");
                    thread.setDaemon(false);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    // Package-local seam for deterministic abandoned-task shutdown tests.
    MediaMetadataExecutor(CatalogOwnership ownership, MediaMetadataStartup startup, ThreadPoolExecutor executor) {
        this.ownership = ownership;
        this.executor = executor;
    }

    public void execute(Runnable task) {
        executor.execute(task);
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
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        boolean finalizationFailed = false;
        try {
            var abandoned = executor.shutdownNow();
            for (Runnable task : abandoned) {
                if (task instanceof NeverStartedTask accepted) {
                    try { accepted.neverStarted(); }
                    catch (RuntimeException | Error failure) {
                        finalizationFailed = true;
                        // Bounded diagnostic: no arbitrary exception text or paths.
                        log.warn("Abandoned metadata task finalization failed");
                    }
                }
            }
        } finally {
            boolean terminated = executor.isTerminated();
            if (finalizationFailed || !terminated) {
                ownership.retainUntilProcessExit();
                log.warn("Metadata shutdown incomplete or finalization failed; catalog ownership retained until process exit");
            }
        }
    }
}
