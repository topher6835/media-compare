package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.PersistedPhysicalActions;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.contentread.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.web.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class ExfatSlice5SchedulerTests {
    private static final ThumbnailGenerationResult UNSUPPORTED = new ThumbnailGenerationResult(ThumbnailGenerationResult.Outcome.UNSUPPORTED, null);
    @TempDir Path directory;

    private final class Fixture implements AutoCloseable {
        final CatalogOwnership ownership = CatalogOwnership.acquire("jdbc:sqlite:" + directory.resolve("owner.db"));
        final ExfatAuthorityWindowRegistry registry = shortDrainRegistry(ownership);
        final List<ExfatContentReadBundles.Batch> admitted = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger(), captures = new AtomicInteger();
        Function<ExfatContentReadCapture, ThumbnailGenerationResult> work = ignored -> UNSUPPORTED;
        boolean cacheHit;
        Runnable onClassification = () -> { };
        List<SourceAuthorityWindowRequest> admittedWindows;
        ExfatAuthorityWindowRegistry.WindowId window;
        final ThumbnailScheduler scheduler;
        Fixture() throws Exception {
            var sourceScope = scope();
            var dataSource = new org.sqlite.SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + directory.resolve("batch.db"));
            var manager = new DataSourceTransactionManager(dataSource);
            var jdbc = new JdbcTemplate(dataSource);
            var jobs = new JobRepository(jdbc) { @Override public void reserveAdmissionWrite() { } };
            var catalog = new ExfatContentReadCatalog(null, null, null, null, null, null) {
                @Override public ExfatAuthorityScope scope(long id) { return sourceScope; }
                @Override public ExfatContentReadCapture capture(ExfatContentReadAuthority owner, long file, long member) {
                    captures.incrementAndGet(); return new ExfatContentReadCapture(owner, null, null, null, null, null);
                }
            };
            var enabled = ExfatSlice5Configuration.enabledBundles(registry, catalog, jobs, manager);
            var bundles = new ExfatContentReadBundles(registry, catalog, null, jobs, manager) {
                @Override public Batch admitBatch(List<SourceAuthorityWindowRequest> windows) {
                    admittedWindows = List.copyOf(windows);
                    var batch = enabled.admitBatch(windows); admitted.add(batch); return batch;
                }
            };
            var physical = new PersistedPhysicalActions(null, null) { @Override public boolean isExfat(long id) { onClassification.run(); return true; } };
            var candidates = new MediaMetadataCandidateRepository(jdbc) {
                @Override public Optional<MediaMetadataFileCandidate> findExfatOccurrence(long id, List<Long> sources) {
                    return Optional.of(new MediaMetadataFileCandidate(1, 10, id, id, 1, 1, CONTEXT, 1, 1,
                            lp(ROOT.append("photo.png")), key(ROOT.append("photo.png")), 1, 10, 500L, 0));
                }
            };
            var thumbnails = new ExfatThumbnailService(null, null, null, null, null) {
                @Override public Optional<ThumbnailGenerationResult> cached(long id) { return cacheHit ? Optional.of(UNSUPPORTED) : Optional.empty(); }
                @Override public ThumbnailGenerationResult generate(ExfatContentReadCapture capture) {
                    calls.incrementAndGet(); return work.apply(capture);
                }
            };
            scheduler = new ThumbnailScheduler(ownership, id -> { fail("Native generator invoked for exFAT"); return null; });
            scheduler.contentReads(physical, bundles, catalog, candidates, thumbnails);
            prepare();
        }
        void prepare() {
            var root = new Root(ROOT);
            try (var attempt = registry.begin(1, root.directoryCount(), CONTEXT)) {
                window = registry.installing(attempt, () -> registry.install(attempt, scope(), root));
            }
        }
        List<SourceAuthorityWindowRequest> windows() { return List.of(new SourceAuthorityWindowRequest(1, window.windowId())); }
        @Override public void close() throws java.io.IOException { scheduler.shutdown(Duration.ofSeconds(10)); registry.destroy(); ownership.close(); }
    }
    private static List<Long> ids(int count) { return java.util.stream.LongStream.rangeClosed(1, count).boxed().toList(); }

    @Test void partialRejectionAndShutdownWaitForActualWorkerExit() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1); var exit = new CountDownLatch(1);
            fixture.work = ignored -> { entered.countDown(); awaitIgnoringInterrupt(exit); return UNSUPPORTED; };
            try {
                var results = fixture.scheduler.schedule(ids(100), fixture.windows());
                await(entered);
                assertEquals(65, results.stream().filter(r -> r.status() == ThumbnailScheduler.Status.QUEUED).count());
                assertEquals(35, results.stream().filter(r -> r.status() == ThumbnailScheduler.Status.QUEUE_FULL).count());
                var batch = fixture.admitted.getFirst(); assertFalse(batch.drained());
                fixture.scheduler.shutdown(Duration.ZERO); assertFalse(batch.drained());
                exit.countDown(); fixture.scheduler.shutdown(Duration.ofSeconds(10));
                assertTrue(batch.drained()); assertEquals(1, fixture.calls.get());
            } finally { exit.countDown(); }
        }
    }

    @Test void oldTaskCannotBorrowOrCoalesceWithNewWindow() throws Exception {
        try (var fixture = new Fixture()) {
            var entered = new CountDownLatch(1); var exit = new CountDownLatch(1);
            fixture.work = ignored -> { entered.countDown(); awaitIgnoringInterrupt(exit); return UNSUPPORTED; };
            try {
                assertEquals(ThumbnailScheduler.Status.QUEUED, fixture.scheduler.schedule(List.of(1L), fixture.windows()).getFirst().status());
                await(entered); fixture.prepare();
                assertEquals(ThumbnailScheduler.Status.AUTHORITY_UNAVAILABLE, fixture.scheduler.schedule(List.of(1L), fixture.windows()).getFirst().status());
                assertTrue(fixture.admitted.get(1).drained()); assertFalse(fixture.admitted.getFirst().drained());
            } finally { exit.countDown(); fixture.scheduler.shutdown(Duration.ofSeconds(10)); }
            assertTrue(fixture.admitted.getFirst().drained()); assertEquals(1, fixture.calls.get());
        }
    }

    @Test void allQueueRejectionSealsAndDrainsEveryAdmittedItem() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.scheduler.shutdown(Duration.ZERO);
            var results = fixture.scheduler.schedule(ids(100), fixture.windows());
            assertTrue(results.stream().allMatch(r -> r.status() == ThumbnailScheduler.Status.QUEUE_FULL));
            assertTrue(fixture.admitted.getFirst().drained()); assertEquals(0, fixture.calls.get());
        }
    }

    @Test void immediateUnsupportedOrFailedTasksDrainSealedBatchOnce() throws Exception {
        try (var fixture = new Fixture()) {
            var attempted = new AtomicInteger();
            fixture.work = ignored -> {
                if (attempted.incrementAndGet() % 2 == 0) throw new ThumbnailGenerationException("Test execution failure");
                return UNSUPPORTED;
            };
            assertThrows(IllegalArgumentException.class, () -> fixture.scheduler.schedule(ids(101), fixture.windows()));
            fixture.scheduler.schedule(ids(100), fixture.windows()); fixture.scheduler.shutdown(Duration.ofSeconds(10));
            assertTrue(fixture.admitted.getFirst().drained());
            assertFalse(fixture.registry.project(scope()).liveAuthorityAvailable());
        }
    }

    @Test void cacheHitNeedsNeitherWindowNorCandidateCapture() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.cacheHit = true;
            assertEquals(ThumbnailScheduler.Status.CACHED, fixture.scheduler.schedule(1).status());
            assertEquals(0, fixture.captures.get()); assertTrue(fixture.admitted.isEmpty()); assertEquals(0, fixture.calls.get());
        }
    }
    @Test void admissionUsesDefensiveCopiesDespiteCallerMutation() throws Exception {
        try (var fixture = new Fixture()) {
            var requestedIds = new ArrayList<>(List.of(3L, 1L, 2L));
            var requestedWindows = new ArrayList<>(fixture.windows());
            var originalWindows = List.copyOf(requestedWindows);
            fixture.onClassification = () -> {
                requestedIds.clear(); requestedIds.add(99L);
                requestedWindows.clear();
                requestedWindows.add(new SourceAuthorityWindowRequest(1, UUID.randomUUID().toString()));
            };
            fixture.scheduler.shutdown(Duration.ZERO);
            var results = fixture.scheduler.schedule(requestedIds, requestedWindows);
            assertEquals(List.of(3L, 1L, 2L), results.stream().map(ThumbnailScheduler.Result::fileEntryId).toList());
            assertTrue(results.stream().allMatch(r -> r.status() == ThumbnailScheduler.Status.QUEUE_FULL));
            assertEquals(originalWindows, fixture.admittedWindows);
            assertEquals(3, fixture.captures.get());
            assertTrue(fixture.admitted.getFirst().drained());
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { assertTrue(latch.await(10, TimeUnit.SECONDS)); break; }
        catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
