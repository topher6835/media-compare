package io.github.topher6835.mediacompare.filesystem;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriod;
import io.github.topher6835.mediacompare.config.CatalogOwnership;

class ExfatAuthorityWindowRegistryTests {
    private CatalogOwnership ownership;
    private ExfatAuthorityWindowRegistry registry;
    private final AtomicLong time = new AtomicLong();

    @BeforeEach void runtime() throws Exception {
        ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:");
        registry = new ExfatAuthorityWindowRegistry(ownership, time::get, Duration.ofMillis(40));
    }
    @AfterEach void shutdown() throws Exception { registry.destroy(); ownership.close(); }

    @Test void bundleDeadlineTracksActualProgressRatherThanTotalRunningTime() {
        var root = new Root(ROOT); var id = acquire(scope(), root); String bundle = UUID.randomUUID().toString();
        registry.associate(scope(), id, bundle, 1, 2);
        try (var retained = registry.retainBundle(scope(), id, bundle, 1, 2)) {
            for (int i = 0; i < 10; i++) {
                time.addAndGet(ExfatAuthorityWindowRegistry.NO_PROGRESS_TIMEOUT.toNanos() / 2);
                retained.progress(); registry.expire(); retained.checkpoint(bundle, 1, 2);
            }
            assertTrue(registry.project(scope()).liveAuthorityAvailable());
            time.addAndGet(ExfatAuthorityWindowRegistry.NO_PROGRESS_TIMEOUT.toNanos()); registry.expire();
            assertTrue(retained.cancelled()); assertEquals(0, root.closes.get(), "IO still retains resources while cancelled");
            assertThrows(IllegalStateException.class, retained::progress);
        }
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, registry.release(id).releaseState());
    }

    @Test void exactRuntimeSourceContextRevisionsRootEvidenceAndPeriodAreRequired() throws Exception {
        var scope = scope();
        var root = new Root(ROOT);
        var id = acquire(scope, root);
        assertEquals(4, UUID.fromString(id.windowId()).version());
        assertEquals(2, UUID.fromString(id.windowId()).variant());
        assertEquals(id.windowId(), UUID.fromString(id.windowId()).toString());
        assertTrue(registry.project(scope).liveAuthorityAvailable());
        try (var operation = registry.requireExact(scope, id)) { operation.revalidate(); }
        for (var wrong : java.util.List.of(scope(2, CONTEXT, 1, 1, ROOT),
                scope(1, "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee", 1, 1, ROOT),
                scope(1, CONTEXT, 2, 1, ROOT), scope(1, CONTEXT, 1, 2, ROOT),
                scope(1, CONTEXT, 1, 1, path("C:\\Elsewhere")))) {
            assertThrows(IllegalStateException.class, () -> registry.requireExact(wrong, id));
            assertFalse(registry.project(wrong).liveAuthorityAvailable());
        }
        var s = scope.source();
        var changedSource = new Source(s.id(), s.name(), s.rootPath(), s.rootPathKey(), s.locationRevision(),
                s.rootPathDialect(), s.boundLocationContextId(), s.bindingEvidenceJson() + " ", s.createdAtMs(), s.updatedAtMs());
        var p = scope.period();
        var changedPeriod = new SourceBindingPeriod(p.id(), p.sourceId(), p.boundSourceLocationRevision(), p.locationContextId(),
                p.rootPathDialect(), p.rootPath(), p.rootPathKey(), changedSource.bindingEvidenceJson(), p.boundAtMs(), null, null);
        var changed = new ExfatAuthorityScope(changedSource, scope.context(), changedPeriod);
        assertThrows(IllegalStateException.class, () -> registry.requireExact(changed, id));
        var otherRuntime = new ExfatAuthorityWindowRegistry(ownership, time::get, Duration.ofMillis(40));
        try {
            assertFalse(otherRuntime.project(scope).liveAuthorityAvailable());
            assertThrows(IllegalStateException.class, () -> otherRuntime.requireExact(scope, id));
            assertThrows(IllegalStateException.class, () -> registry.requireExact(scope,
                    new ExfatAuthorityWindowRegistry.WindowId(otherRuntime.runtimeId(), 1, id.windowId())));
        } finally { otherRuntime.destroy(); }
    }

    @Test void replacementAndReleaseCannotReviveOldUuidAtIdenticalDurableRevisions() {
        var firstRoot = new Root(ROOT);
        var old = acquire(scope(), firstRoot);
        var newRoot = new Root(ROOT);
        var current = acquire(scope(), newRoot);
        assertNotEquals(old.windowId(), current.windowId());
        assertThrows(IllegalStateException.class, () -> registry.requireExact(scope(), old));
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, registry.release(old).releaseState());
        assertEquals(1, firstRoot.closes.get());
        assertTrue(registry.project(scope()).liveAuthorityAvailable());
        assertEquals(0, newRoot.closes.get());
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, registry.release(current).releaseState());
        assertEquals(1, newRoot.closes.get());
        registry.release(current);
        assertEquals(1, newRoot.closes.get());
        assertFalse(registry.project(scope()).liveAuthorityAvailable());
    }

    @Test void releaseDoesNotCloseResourcesBeforePausedPublicationCommitAndReportsDraining() throws Exception {
        var root = new Root(ROOT);
        var id = acquire(scope(), root);
        var entered = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                try (var operation = registry.requireExact(scope(), id)) {
                    entered.countDown();
                    assertTrue(commit.await(5, TimeUnit.SECONDS)); // Read gate spans the simulated transaction commit.
                }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING, registry.release(id).releaseState());
            assertFalse(registry.project(scope()).liveAuthorityAvailable());
            assertEquals(0, root.closes.get());
            commit.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, registry.release(id).releaseState());
            assertEquals(1, root.closes.get());
        } finally { commit.countDown(); }
    }

    @Test void timeoutPendingAttemptCancellationShutdownAndNativeFailureAreTransient() throws Exception {
        var root = new Root(ROOT);
        var id = acquire(scope(), root);
        time.set(ExfatAuthorityWindowRegistry.PREPARE_TIMEOUT.toNanos());
        assertFalse(registry.project(scope()).liveAuthorityAvailable());
        assertThrows(IllegalStateException.class, () -> registry.requireExact(scope(), id));
        registry.expire();
        registry.release(id);
        assertEquals(1, root.closes.get());
        var nextRoot = new Root(ROOT);
        var next = acquire(scope(), nextRoot);
        assertNotEquals(id, next);
        nextRoot.fail = true;
        try (var operation = registry.requireExact(scope(), next)) {
            assertThrows(java.io.IOException.class, operation::revalidate);
            assertTrue(operation.cancelled());
        }
        registry.release(next);
        assertEquals(1, nextRoot.closes.get());
        try (var attempt = registry.begin(1, 2)) {
            time.addAndGet(ExfatAuthorityWindowRegistry.PREPARE_TIMEOUT.toNanos());
            registry.expire();
            assertThrows(IllegalStateException.class, attempt::checkpoint);
        }
        var finalRoot = new Root(ROOT);
        acquire(scope(), finalRoot);
        try (var attempt = registry.begin(2, 2)) {
            registry.shutdown();
            assertThrows(IllegalStateException.class, attempt::checkpoint);
        }
        registry.destroy();
        assertEquals(1, finalRoot.closes.get());
        assertThrows(IllegalStateException.class, () -> registry.begin(1, 2));
    }

    @Test void staleReleaseDoesNotCancelNewAttemptButCurrentReleaseDoes() {
        var old = acquire(scope(), new Root(ROOT));
        registry.release(old);
        try (var attempt = registry.begin(1, 2)) {
            registry.release(old);
            attempt.checkpoint();
            registry.installing(attempt, () -> registry.install(attempt, scope(), new Root(ROOT)));
        }
        var current = registry.capturePrepared(scope());
        try (var attempt = registry.begin(1, 2)) {
            registry.release(current);
            assertThrows(IllegalStateException.class, attempt::checkpoint);
            var rejected = new Root(ROOT);
            assertThrows(IllegalStateException.class,
                    () -> registry.installing(attempt, () -> registry.install(attempt, scope(), rejected)));
            rejected.close();
        }
    }

    @Test void repeatedAndConcurrentAttemptsBoundsAndIdempotentRetain() {
        var root = new Root(ROOT);
        var id = acquire(scope(), root);
        try (var attempt = registry.begin(1, 2)) {
            assertThrows(IllegalStateException.class, () -> registry.begin(1, 2));
            assertEquals(id, registry.installing(attempt, () -> registry.retain(attempt, scope(), id)));
        }
        assertEquals(0, root.closes.get());
        var attempts = new ArrayList<ExfatAuthorityWindowRegistry.Attempt>();
        try {
            for (int i = 2; i <= 16; i++) attempts.add(registry.begin(i, 257));
            assertThrows(IllegalStateException.class, () -> registry.begin(17, 257));
            assertThrows(IllegalStateException.class, () -> registry.begin(17, 258));
        } finally { attempts.forEach(ExfatAuthorityWindowRegistry.Attempt::close); }
    }

    @Test void concurrentPrepareAttemptAndPreparedSourceLimitFailClosed() throws Exception {
        try (var attempt = registry.begin(1, 2); var executor = Executors.newSingleThreadExecutor()) {
            assertInstanceOf(IllegalStateException.class,
                    executor.submit(() -> assertThrows(IllegalStateException.class, () -> registry.begin(1, 2)))
                            .get(5, TimeUnit.SECONDS));
        }
        for (int i = 1; i <= 64; i++) {
            var root = path("C:\\");
            acquire(scope(i, CONTEXT, 1, 1, root), new Root(root));
        }
        assertThrows(IllegalStateException.class, () -> registry.begin(65, 1));
        try (var revalidate = registry.begin(1, 1)) { revalidate.checkpoint(); }
    }

    @Test void uncertainCloseStaysDrainingAndNeverRestoresAuthorityOrRetriesClose() {
        var root = new Root(ROOT);
        root.closeFails = true;
        var id = acquire(scope(), root);
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING, registry.release(id).releaseState());
        assertFalse(registry.project(scope()).liveAuthorityAvailable());
        assertThrows(IllegalStateException.class, () -> registry.begin(1, 2));
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING, registry.release(id).releaseState());
        assertEquals(1, root.closes.get());
    }

    @Test void retainedChainCannotBeBorrowedByAnotherRuntimeAndContextLossCancelsPendingAcquisition() {
        var held = new Root(ROOT);
        var id = acquire(scope(), held);
        var other = new ExfatAuthorityWindowRegistry(ownership, time::get, Duration.ofMillis(40));
        try {
            try (var attempt = other.begin(1, 2, CONTEXT)) {
                assertThrows(IllegalStateException.class,
                        () -> other.installing(attempt, () -> other.install(attempt, scope(), held)));
            }
            assertTrue(registry.project(scope()).liveAuthorityAvailable());
            assertFalse(other.project(scope()).liveAuthorityAvailable());
            try (var attempt = registry.begin(2, 2, CONTEXT)) {
                registry.invalidateVolume(CONTEXT);
                assertThrows(IllegalStateException.class, attempt::checkpoint);
            }
            registry.release(id);
        } finally { other.destroy(); }
    }

    @Test void pendingAttemptsRequireExactRuntimeSourceAndContextOwnership() {
        var other = new ExfatAuthorityWindowRegistry(ownership, time::get, Duration.ofMillis(40));
        try {
            try (var foreign = other.begin(1, 2, CONTEXT)) {
                assertThrows(IllegalStateException.class, () -> registry.installing(foreign, () -> null));
                foreign.checkpoint();
            }
            String wrongContext = "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee";
            try (var attempt = registry.begin(1, 2, wrongContext)) {
                var held = new Root(ROOT);
                assertThrows(IllegalStateException.class,
                        () -> registry.installing(attempt, () -> registry.install(attempt, scope(), held)));
                held.close();
            }
            var id = acquire(scope(), new Root(ROOT));
            try (var wrongSource = registry.begin(2, 2, CONTEXT)) {
                assertThrows(IllegalStateException.class,
                        () -> registry.installing(wrongSource, () -> registry.retain(wrongSource, scope(), id)));
            }
            assertTrue(registry.project(scope()).liveAuthorityAvailable());
        } finally { other.destroy(); }
    }

    @Test void operationLeaseCannotBeClosedOrRevalidatedByAnotherThread() throws Exception {
        var root = new Root(ROOT);
        var id = acquire(scope(), root);
        try (var operation = registry.requireExact(scope(), id); var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThrows(IllegalStateException.class, operation::close)).get(5, TimeUnit.SECONDS);
            executor.submit(() -> assertThrows(IllegalStateException.class, operation::revalidate)).get(5, TimeUnit.SECONDS);
            assertTrue(registry.project(scope()).liveAuthorityAvailable());
            assertEquals(0, root.closes.get());
        }
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, registry.release(id).releaseState());
        assertEquals(1, root.closes.get());
    }

    @Test void bundleOwnershipHandoffTimeoutAndIndependentSourceRelease() {
        var a = acquire(scope(), new Root(ROOT));
        var bScope = scope(2, CONTEXT, 1, 1, path("C:\\Photos\\Nested"));
        var bRoot = new Root(path("C:\\Photos\\Nested"));
        var b = acquire(bScope, bRoot);
        var unrelatedScope = scope(3, CONTEXT, 1, 1, path("C:\\Elsewhere"));
        acquire(unrelatedScope, new Root(path("C:\\Elsewhere")));
        String bundle = UUID.randomUUID().toString();
        registry.associate(scope(), a, bundle, 1, 2);
        registry.associate(bScope, b, bundle, 1, 2);
        assertThrows(IllegalStateException.class, () -> registry.requireBundle(scope(), a, UUID.randomUUID().toString(), 2));
        assertThrows(IllegalStateException.class, () -> registry.requireBundle(scope(), a, bundle, 3));
        try (var operation = registry.requireBundle(scope(), a, bundle, 2)) { assertFalse(operation.cancelled()); }
        registry.release(a);
        registry.release(b);
        assertEquals(1, bRoot.closes.get());
        assertTrue(registry.project(unrelatedScope).liveAuthorityAvailable());
        var c = acquire(scope(), new Root(ROOT));
        registry.associate(scope(), c, bundle, 3, 4);
        registry.handoff(scope(), c, bundle);
        time.addAndGet(ExfatAuthorityWindowRegistry.HANDOFF_TIMEOUT.toNanos());
        registry.expire();
        assertFalse(registry.project(scope()).liveAuthorityAvailable());
        registry.release(c);
    }

    private ExfatAuthorityWindowRegistry.WindowId acquire(ExfatAuthorityScope scope, Root root) {
        try (var attempt = registry.begin(scope.source().id(), root.directoryCount())) {
            return registry.installing(attempt, () -> registry.install(attempt, scope, root));
        }
    }
}
