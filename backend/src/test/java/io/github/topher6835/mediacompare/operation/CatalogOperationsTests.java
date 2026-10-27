package io.github.topher6835.mediacompare.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.matching.CleanupPreflightRequest;
import org.junit.jupiter.api.Test;

class CatalogOperationsTests {
    private static final CatalogOperationRequest PREPARE = new CatalogOperationRequest(
            CatalogOperationRequest.Kind.PREPARE_SOURCE, 1L, null, null, null);
    private static final CatalogOperationRequest CHECK = new CatalogOperationRequest(
            CatalogOperationRequest.Kind.CLEANUP_PREFLIGHT, null, null, "a".repeat(64),
            new CleanupPreflightRequest(1L, List.of(2L)));

    @Test void reservationDoesNothingAndRetriesReturnTheOriginalResultWithoutRunningAgain() throws Exception {
        var calls = new AtomicInteger();
        var operations = operations(request -> { calls.incrementAndGet(); return Map.of("value", 42); });
        try {
            String id = operations.reserve().operationId();
            assertEquals(CatalogOperations.State.RESERVED, operations.status(id).status());
            assertEquals(0, calls.get());
            operations.submit(id, PREPARE);
            var done = await(operations, id, status -> status.status() == CatalogOperations.State.SUCCEEDED);
            assertEquals(42, done.result().get("value").intValue());
            assertNotNull(done.startedAtMs());
            assertNotNull(done.completedAtMs());
            assertEquals(done, operations.submit(id, PREPARE));
            assertEquals(1, calls.get());
            assertEquals(409, assertThrows(CatalogOperationFailure.class, () -> operations.submit(id, CHECK)).status());
            assertEquals(1, calls.get());
        } finally { operations.destroy(); }
    }

    @Test void oneRunningOneQueuedAndFastStatusWhileWorkIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var operations = operations(request -> {
            calls.incrementAndGet();
            assertFalse(Thread.currentThread().isDaemon());
            entered.countDown();
            waitFor(release);
            return null;
        });
        try {
            String first = operations.reserve().operationId();
            String second = operations.reserve().operationId();
            String third = operations.reserve().operationId();
            operations.submit(first, PREPARE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(CatalogOperations.State.RUNNING, operations.status(first).status());
            assertEquals(CatalogOperations.State.QUEUED, operations.submit(second, CHECK).status());
            assertEquals(429, assertThrows(CatalogOperationFailure.class, () -> operations.submit(third, PREPARE)).status());
            assertEquals(1, calls.get());
            assertEquals(CatalogOperations.State.RUNNING, operations.submit(first, PREPARE).status());
            assertEquals(CatalogOperations.State.CANCELLED, operations.cancel(second).status());
            assertEquals(1, calls.get());
            assertEquals(409, assertThrows(CatalogOperationFailure.class, () -> operations.cancel(first)).status());
            release.countDown();
            await(operations, first, status -> status.status() == CatalogOperations.State.SUCCEEDED);
            assertEquals(1, calls.get());
        } finally { release.countDown(); operations.destroy(); }
    }

    @Test void cancellingAnUncooperativeCleanupKeepsItsSlotAndNeverPublishesReady() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var operations = operations(request -> {
            entered.countDown();
            // Deliberately ignore interruption to model native IO that has not yet returned.
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return Map.of("status", "READY");
        });
        try {
            String id = operations.reserve().operationId();
            operations.submit(id, CHECK);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(CatalogOperations.State.CANCELLING, operations.cancel(id).status());
            assertEquals(CatalogOperations.State.CANCELLING, operations.status(id).status());
            release.countDown();
            var done = await(operations, id, status -> status.status() == CatalogOperations.State.CANCELLED);
            assertNull(done.result());
            assertEquals(CatalogOperations.State.CANCELLED, operations.submit(id, CHECK).status());
        } finally { release.countDown(); operations.destroy(); }
    }

    @Test void completedCleanupCanBeDiscardedAndANewReservationDoesFreshWork() throws Exception {
        var calls = new AtomicInteger();
        var operations = operations(request -> Map.of("status", calls.incrementAndGet() == 1 ? "READY" : "BLOCKED"));
        try {
            String first = operations.reserve().operationId();
            operations.submit(first, CHECK);
            assertEquals("READY", await(operations, first, s -> s.status() == CatalogOperations.State.SUCCEEDED).result().get("status").stringValue());
            assertNull(operations.cancel(first).result());
            String second = operations.reserve().operationId();
            operations.submit(second, CHECK);
            assertEquals("BLOCKED", await(operations, second, s -> s.status() == CatalogOperations.State.SUCCEEDED).result().get("status").stringValue());
            assertEquals(2, calls.get());
        } finally { operations.destroy(); }
    }

    @Test void knownAndUnexpectedFailuresAreTerminalAndNeverExposeExceptionText() throws Exception {
        for (Function<CatalogOperationRequest, Object> action : List.<Function<CatalogOperationRequest, Object>>of(
                request -> { throw new CatalogOperationFailure(422, "PATH_UNAVAILABLE"); },
                request -> { throw new IllegalStateException("secret filesystem path"); })) {
            var operations = operations(action);
            try {
                String id = operations.reserve().operationId();
                operations.submit(id, PREPARE);
                var result = await(operations, id, s -> s.status() == CatalogOperations.State.FAILED);
                assertTrue(result.errorStatus() == 422 || result.errorStatus() == 500);
                assertFalse(result.toString().contains("secret filesystem path"));
                assertEquals(result, operations.submit(id, PREPARE));
            } finally { operations.destroy(); }
        }
    }

    @Test void resultAndRecordBoundsAndExpiryCannotTurnRetriesIntoNewExecutions() throws Exception {
        var ticks = new AtomicLong();
        var calls = new AtomicInteger();
        var operations = new CatalogOperations(CatalogOwnership.acquire("jdbc:sqlite::memory:"), request -> {
            calls.incrementAndGet();
            return "a".repeat(65_537);
        }, ticks::get, Clock.systemUTC(), Duration.ofMinutes(10));
        try {
            String completed = operations.reserve().operationId();
            operations.submit(completed, PREPARE);
            assertEquals("RESULT_TOO_LARGE", await(operations, completed, s -> s.status() == CatalogOperations.State.FAILED).errorCode());
            String reserved = operations.reserve().operationId();
            for (int index = 0; index < 62; index++) operations.reserve();
            assertNotNull(operations.reserve()); // Terminal results may be evicted under cache pressure.
            assertEquals(410, assertThrows(CatalogOperationFailure.class, () -> operations.submit(completed, PREPARE)).status());
            assertEquals(429, assertThrows(CatalogOperationFailure.class, operations::reserve).status());
            ticks.set(Duration.ofMinutes(11).toNanos());
            assertEquals(410, assertThrows(CatalogOperationFailure.class, () -> operations.status(completed)).status());
            assertEquals(410, assertThrows(CatalogOperationFailure.class, () -> operations.submit(completed, PREPARE)).status());
            assertEquals(410, assertThrows(CatalogOperationFailure.class, () -> operations.submit(reserved, PREPARE)).status());
            assertEquals(1, calls.get());
            assertNotNull(operations.reserve());
            var other = operations(request -> null);
            try { assertEquals(409, assertThrows(CatalogOperationFailure.class, () -> other.submit(completed, PREPARE)).status()); }
            finally { other.destroy(); }
        } finally { operations.destroy(); }
    }

    @Test void shutdownCancelsQueuedWorkAndRetainsOwnershipUntilUncooperativeWorkExits() throws Exception {
        var ownership = mock(CatalogOwnership.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var operations = new CatalogOperations(ownership, request -> {
            calls.incrementAndGet();
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return null;
        }, System::nanoTime, Clock.systemUTC(), Duration.ofMinutes(10));
        try {
            String first = operations.reserve().operationId();
            String queued = operations.reserve().operationId();
            operations.submit(first, PREPARE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            operations.submit(queued, CHECK);
            operations.shutdown(Duration.ZERO);
            verify(ownership).retainUntilProcessExit();
            assertEquals(CatalogOperations.State.CANCELLING, operations.status(first).status());
            assertEquals(CatalogOperations.State.CANCELLED, operations.status(queued).status());
            assertEquals(503, assertThrows(CatalogOperationFailure.class, operations::reserve).status());
            assertEquals(1, calls.get());
            release.countDown();
            await(operations, first, s -> s.status() == CatalogOperations.State.CANCELLED);
        } finally { release.countDown(); operations.destroy(); }
    }

    private CatalogOperations operations(Function<CatalogOperationRequest, Object> action) throws Exception {
        return new CatalogOperations(CatalogOwnership.acquire("jdbc:sqlite::memory:"), action,
                System::nanoTime, Clock.systemUTC(), Duration.ofMinutes(10));
    }
    private static void waitFor(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static CatalogOperations.Status await(CatalogOperations operations, String id,
            Predicate<CatalogOperations.Status> predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            var status = operations.status(id);
            if (predicate.test(status)) return status;
            Thread.sleep(5);
        }
        throw new AssertionError(operations.status(id));
    }
}
