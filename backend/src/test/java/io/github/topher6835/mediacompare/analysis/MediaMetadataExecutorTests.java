package io.github.topher6835.mediacompare.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.topher6835.mediacompare.config.CatalogOwnership;

import org.junit.jupiter.api.Test;

class MediaMetadataExecutorTests {

    @Test
    void oneNamedWorkerOneQueuedTaskAndAbortWithoutCallerRuns() throws Exception {
        try (CatalogOwnership ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            MediaMetadataExecutor executor = new MediaMetadataExecutor(ownership, null);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch queuedDone = new CountDownLatch(1);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicReference<String> threadName = new AtomicReference<>();
            try {
                executor.execute(() -> {
                    threadName.set(Thread.currentThread().getName());
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    entered.countDown();
                    await(release);
                    active.decrementAndGet();
                });
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                executor.execute(() -> {
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    active.decrementAndGet();
                    queuedDone.countDown();
                });

                assertThrows(RejectedExecutionException.class,
                        () -> executor.execute(() -> fail("caller runs")));
                assertEquals(1, queuedDone.getCount());
                assertEquals("media-compare-metadata", threadName.get());
            } finally {
                release.countDown();
                executor.destroy();
            }
            assertEquals(0, queuedDone.getCount());
            assertEquals(1, maximum.get());
        }
    }

    @Test
    void zeroShutdownBudgetInterruptsWorkerAndDiscardsQueuedTask() throws Exception {
        try (CatalogOwnership ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            MediaMetadataExecutor executor = new MediaMetadataExecutor(ownership, null);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch queued = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            executor.execute(queued::countDown);

            executor.shutdown(Duration.ZERO);

            assertTrue(interrupted.await(10, TimeUnit.SECONDS));
            assertEquals(1, queued.getCount());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fixture latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
