package io.github.topher6835.mediacompare.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadAuthority;
import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyAbandonedTaskIsAttemptedAndFailedFinalizationRetainsOwnership(boolean terminated) throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("failed.db");
        try (var ownership = CatalogOwnership.acquire(url)) {
            var registry = shortDrainRegistry(ownership);
            var scope = scope();
            var root = new Root(ROOT);
            io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry.WindowId window;
            try (var attempt = registry.begin(1, root.directoryCount(), CONTEXT)) {
                window = registry.installing(attempt, () -> registry.install(attempt, scope, root));
            }
            var owner = new ExfatContentReadAuthority(scope, window, UUID.randomUUID().toString(), 1L, null);
            registry.transition(() -> { registry.associateContent(List.of(owner), null); return null; });
            var attempts = new ArrayList<Integer>();
            var released = new AtomicInteger();
            var abandoned = new ArrayList<Runnable>();
            for (int id = 0; id < 3; id++) {
                final int taskId = id;
                abandoned.add(new MediaMetadataExecutor.NeverStartedTask() {
                    @Override public void run() { fail("Abandoned task executed"); }
                    @Override public void neverStarted() {
                        attempts.add(taskId);
                        try {
                            assertThrows(IllegalStateException.class, () -> registry.retainContent(owner));
                            if (taskId == 0) throw new IllegalStateException("Injected recovery write failure");
                        } finally { released.incrementAndGet(); }
                    }
                });
            }
            var pool = new ShutdownPool(abandoned, terminated);
            var executor = new MediaMetadataExecutor(ownership, null, pool);
            executor.exfatLifecycle(registry);
            executor.destroy();
            assertEquals(List.of(0, 1, 2), attempts);
            assertEquals(3, released.get());
            assertTrue(pool.terminationChecked);
            assertThrows(IllegalStateException.class, () -> registry.retainContent(owner));
            ownership.close();
            assertThrows(IllegalStateException.class, () -> CatalogOwnership.acquire(url));
            registry.destroy();
        }
    }

    @Test
    void successfulAbandonedFinalizationWithTerminatedExecutorReleasesCatalogOwnership() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("successful.db");
        var ownership = CatalogOwnership.acquire(url);
        var finalized = new AtomicInteger();
        var task = new MediaMetadataExecutor.NeverStartedTask() {
            @Override public void run() { fail("Abandoned task executed"); }
            @Override public void neverStarted() { finalized.incrementAndGet(); }
        };
        var pool = new ShutdownPool(List.of(task), true);
        new MediaMetadataExecutor(ownership, null, pool).shutdown(Duration.ZERO);
        assertEquals(1, finalized.get());
        assertTrue(pool.terminationChecked);
        ownership.close();
        try (var reacquired = CatalogOwnership.acquire(url)) { assertEquals(ownership.lockPath(), reacquired.lockPath()); }
    }

    /** No worker/native IO; deterministically returns several abandoned tasks despite the production queue bound. */
    private static final class ShutdownPool extends ThreadPoolExecutor {
        private final List<Runnable> abandoned;
        private final boolean terminated;
        private boolean terminationChecked;
        ShutdownPool(List<Runnable> abandoned, boolean terminated) {
            super(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
            this.abandoned = List.copyOf(abandoned); this.terminated = terminated;
        }
        @Override public void shutdown() { }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
        @Override public List<Runnable> shutdownNow() { return abandoned; }
        @Override public boolean isTerminated() { terminationChecked = true; return terminated; }
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
