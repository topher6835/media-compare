package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.preview.ThumbnailScheduler.Status.*;
import java.time.Duration;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ThumbnailSchedulerTests {
    private static final ThumbnailGenerationResult UNSUPPORTED =
            new ThumbnailGenerationResult(ThumbnailGenerationResult.Outcome.UNSUPPORTED, null);

    @Test
    void queueHasExactly64SlotsAndOneNamedNonDaemonWorkerWithCoalescedIds() throws Exception {
        try (var ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var calls = new AtomicInteger();
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            Set<Thread> workers = ConcurrentHashMap.newKeySet();
            var scheduler = new ThumbnailScheduler(ownership, id -> {
                workers.add(Thread.currentThread());
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                calls.incrementAndGet();
                try {
                    if (id == 1) { entered.countDown(); await(release); }
                    return UNSUPPORTED;
                } finally { active.decrementAndGet(); }
            });
            try {
                assertEquals(QUEUED, scheduler.schedule(1).status());
                await(entered);
                assertEquals(ALREADY_QUEUED, scheduler.schedule(1).status());
                for (long id = 2; id <= 65; id++) assertEquals(QUEUED, scheduler.schedule(id).status());
                assertEquals(ALREADY_QUEUED, scheduler.schedule(2).status());
                assertEquals(QUEUE_FULL, scheduler.schedule(66).status());
                assertEquals(QUEUE_FULL, scheduler.schedule(67).status());
                assertEquals(1, calls.get());
            } finally {
                release.countDown();
                scheduler.shutdown(Duration.ofSeconds(10));
            }
            assertEquals(65, calls.get());
            assertEquals(1, maximum.get());
            assertEquals(1, workers.size());
            assertEquals("media-compare-thumbnails", workers.iterator().next().getName());
            assertFalse(workers.iterator().next().isDaemon());
            assertEquals(QUEUE_FULL, scheduler.schedule(1).status());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GENERATED", "REUSED", "UNSUPPORTED", "FAILED"})
    void completionOrFailureReleasesTheIdAndDoesNotKillTheWorker(String outcome) throws Exception {
        try (var ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            var barrierEntered = new CountDownLatch(1);
            var releaseBarrier = new CountDownLatch(1);
            var calls = new AtomicInteger();
            Set<Thread> workers = ConcurrentHashMap.newKeySet();
            var scheduler = new ThumbnailScheduler(ownership, id -> {
                workers.add(Thread.currentThread());
                if (id == 2) {
                    // This cannot start until ID 1's completion finally has released its coalescing state.
                    barrierEntered.countDown();
                    await(releaseBarrier);
                    return UNSUPPORTED;
                }
                if (calls.incrementAndGet() == 1) {
                    if (outcome.equals("FAILED")) throw new ThumbnailGenerationException("fixture failure");
                    if (!outcome.equals("UNSUPPORTED")) return new ThumbnailGenerationResult(
                            ThumbnailGenerationResult.Outcome.valueOf(outcome), PreviewTestFixtures.asset(
                                    PreviewTestFixtures.evidence(), PreviewKind.SMALL_THUMBNAIL,
                                    PreviewTestFixtures.definition(), 6));
                }
                return UNSUPPORTED;
            });
            try {
                assertEquals(QUEUED, scheduler.schedule(1).status());
                assertEquals(QUEUED, scheduler.schedule(2).status());
                await(barrierEntered);
                assertEquals(QUEUED, scheduler.schedule(1).status());
            } finally {
                releaseBarrier.countDown();
                scheduler.shutdown(Duration.ofSeconds(10));
            }
            assertEquals(2, calls.get());
            assertEquals(1, workers.size());
        }
    }

    @Test
    void nonpositiveIdsAreRejectedWithoutExecutingAnything() throws Exception {
        try (var ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            var scheduler = new ThumbnailScheduler(ownership, id -> { fail("invalid task ran"); return UNSUPPORTED; });
            try {
                assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(0));
                assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(-1));
            } finally { scheduler.destroy(); }
        }
    }

    @Test
    void shutdownBudgetInterruptsActiveWorkAndDiscardsQueuedWorkWithoutLeakingIds() throws Exception {
        try (var ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:")) {
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var queuedCalls = new AtomicInteger();
            var scheduler = new ThumbnailScheduler(ownership, id -> {
                if (id == 1) {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException failure) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                } else queuedCalls.incrementAndGet();
                return UNSUPPORTED;
            });
            try {
                scheduler.schedule(1);
                await(entered);
                scheduler.schedule(2);
                scheduler.shutdown(Duration.ZERO);
                await(interrupted);
            } finally { scheduler.shutdown(Duration.ofSeconds(10)); }
            assertEquals(0, queuedCalls.get());
            assertEquals(QUEUE_FULL, scheduler.schedule(1).status());
            assertEquals(QUEUE_FULL, scheduler.schedule(2).status());
        }
    }

    @TempDir Path directory;

    @Test
    void uncooperativeWorkRetainsCatalogOwnershipUntilProcessExit() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process probe = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                RetentionProbe.class.getName(), "jdbc:sqlite:" + directory.resolve("catalog.db"))
                .redirectErrorStream(true).start();
        try {
            assertTrue(probe.waitFor(15, TimeUnit.SECONDS), "ownership probe timed out");
            assertEquals(0, probe.exitValue(), new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { if (probe.isAlive()) probe.destroyForcibly(); }
    }

    public static class RetentionProbe {
        public static void main(String[] args) throws Exception {
            var ownership = CatalogOwnership.acquire(args[0]);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var scheduler = new ThumbnailScheduler(ownership, id -> {
                entered.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException failure) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return UNSUPPORTED;
            });
            try {
                scheduler.schedule(1);
                await(entered);
                scheduler.shutdown(Duration.ZERO);
                ownership.close();
                assertThrows(IllegalStateException.class, () -> CatalogOwnership.acquire(args[0]));
            } finally {
                release.countDown();
                scheduler.shutdown(Duration.ofSeconds(10));
            }
            // The OS releases the intentionally retained lock only when this probe JVM exits.
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "fixture latch timed out"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted", interrupted);
        }
    }
}
