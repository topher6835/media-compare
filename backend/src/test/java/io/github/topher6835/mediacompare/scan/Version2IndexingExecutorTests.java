package io.github.topher6835.mediacompare.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.junit.jupiter.api.Test;

class Version2IndexingExecutorTests {
    @Test
    void oneNamedWorkerOneQueuedTaskAndAbortWithoutCallerRuns() throws Exception {
        try (CatalogOwnership ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            Version2IndexingExecutor executor = new Version2IndexingExecutor(ownership, null);
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
                assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("caller runs")));
                assertEquals(1, queuedDone.getCount());
                assertEquals("media-compare-indexing", threadName.get());
            } finally {
                release.countDown();
                executor.destroy();
            }
            assertEquals(0, queuedDone.getCount());
            assertEquals(1, maximum.get());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("closed executor")));
        }
    }

    @Test
    void shutdownBudgetInterruptsWorkerAndDiscardsQueuedTask() throws Exception {
        try (CatalogOwnership ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            Version2IndexingExecutor executor = new Version2IndexingExecutor(ownership, null);
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
            assertTimeout(Duration.ofSeconds(2), () -> executor.shutdown(Duration.ZERO));
            assertTrue(interrupted.await(10, TimeUnit.SECONDS));
            assertEquals(1, queued.getCount());
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
