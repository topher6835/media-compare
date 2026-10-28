package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.HostFileSystem;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.session.Session;
import io.github.topher6835.mediacompare.session.SessionCreationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCreationOperationsTests {
    @TempDir Path directory;

    @Test void reservationDiscoveryCreationAndReplayRetainActualResultWithoutInitializingCatalog() throws Exception {
        Path parent = directory.toRealPath();
        Files.writeString(parent.resolve("Café 日本"), "original");
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String id = fixture.reserve();
            assertEquals("RESERVED", fixture.operations.status(id).get("state"));
            assertTrue(fixture.operations.list().toString().contains(id));
            assertFalse(Files.exists(parent.resolve("Café 日本 (001)")));
            var input = new SessionCreationOperations.Input(parent.toString(), "Café 日本");
            fixture.operations.submit(id, input);
            var opening = await(fixture.operations, id, state("OPENING"));
            assertEquals("Café 日本 (001)", opening.get("name"));
            assertEquals(parent.resolve("Café 日本 (001)").toString(), opening.get("path"));
            assertEquals(fixture.generation.get(), opening.get("catalogId"));
            assertEquals(true, opening.get("sessionCreated"));
            assertEquals(true, opening.get("processAlive"));
            assertFalse(Files.exists(parent.resolve("Café 日本 (001)/catalog.db")));
            fixture.running();
            var ready = await(fixture.operations, id, state("RUNNING"));
            assertEquals(ready.get("catalogId"), fixture.operations.submit(id, input).get("catalogId"));
            assertEquals(1, fixture.starts.get());
            assertFailure(409, "OPERATION_INPUT_CHANGED", () -> fixture.operations.submit(id,
                    new SessionCreationOperations.Input(parent.toString(), "Changed")));
            assertEquals("original", Files.readString(parent.resolve("Café 日本")));
            fixture.failed(false);
            var stopped = fixture.operations.status(id);
            assertEquals("RUNNING", stopped.get("state")); // Observed startup success remains historical fact.
            assertEquals(false, stopped.get("processAlive"));
            fixture.generation.set(UUID.randomUUID().toString());
            fixture.running();
            var replaced = fixture.operations.status(id);
            assertEquals(false, replaced.get("catalogMatchesCurrent"));
            assertNull(replaced.get("processAlive"));
        }
    }

    @Test void duplicateConcurrentSubmissionRunsOnceAndExcludesOpenAndOtherCreationsDuringIo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger creates = new AtomicInteger();
        try (var fixture = new Fixture(input -> {
            creates.incrementAndGet(); entered.countDown(); waitFor(release);
            return SessionCreationActions.create(input);
        })) {
            try {
                String id = fixture.reserve();
                var input = input("Only one");
                var deliveries = java.util.stream.IntStream.range(0, 8).mapToObj(index ->
                        CompletableFuture.runAsync(() -> fixture.operations.submit(id, input))).toList();
                for (var delivery : deliveries) delivery.get(3, TimeUnit.SECONDS);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(1, creates.get());
                assertEquals("CREATING", fixture.operations.status(id).get("state"));
                assertFailure(409, "LIFECYCLE_BUSY", fixture.admission::acquire);
                String competitor = fixture.reserve();
                assertFailure(409, "LIFECYCLE_BUSY", () -> fixture.operations.submit(competitor, input("Other")));
                assertFailure(409, "OPERATION_INPUT_CHANGED", () -> fixture.operations.submit(competitor, input("Changed after busy")));
                assertFalse(Files.exists(directory.resolve("Other")));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> fixture.catalog.status());
            } finally { release.countDown(); }
        }
    }

    @Test void existingOpenAdmissionAndFailedLiveCatalogBothExcludeNew() throws Exception {
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String id = fixture.reserve();
            try (var lease = fixture.admission.acquire()) {
                assertFailure(409, "LIFECYCLE_BUSY", () -> fixture.operations.submit(id, input("New")));
                assertFalse(Files.exists(directory.resolve("New")));
            }
            fixture.failed(true);
            assertFailure(409, "LIFECYCLE_BUSY", () -> fixture.operations.submit(id, input("New")));
            assertFailure(409, "LIFECYCLE_BUSY", fixture.admission::acquire);
            fixture.failed(false);
            fixture.operations.submit(id, input("New"));
            await(fixture.operations, id, state("OPENING"));
        }
    }

    @Test void retryUsesExactSessionAndFreshAttemptIdentityWithoutRecreatingOrChangingData() throws Exception {
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String creation = fixture.reserve();
            fixture.operations.submit(creation, input("Kept"));
            var created = await(fixture.operations, creation, state("OPENING"));
            Path root = Path.of((String) created.get("path"));
            Files.writeString(root.resolve("catalog.db"), "preserve existing catalog");
            fixture.failed(true);
            await(fixture.operations, creation, state("OPEN_FAILED"));
            assertFailure(409, "RETRY_CHILD_ALIVE", () -> fixture.operations.reserveRetry(creation, creation));
            fixture.failed(false);
            String retry = (String) fixture.operations.reserveRetry(creation, creation).get("operationId");
            String staleSibling = (String) fixture.operations.reserveRetry(creation, creation).get("operationId");
            assertFailure(409, "OPERATION_KIND_CHANGED", () -> fixture.operations.submit(retry, input("Another creation")));
            fixture.operations.submitRetry(retry);
            var opening = await(fixture.operations, retry, state("OPENING"));
            assertEquals(root.toString(), opening.get("path"));
            assertEquals(retry, fixture.operations.status(creation).get("latestAttemptId"));
            assertNotEquals(created.get("catalogId"), opening.get("catalogId"));
            fixture.failed(false);
            await(fixture.operations, retry, state("OPEN_FAILED"));
            fixture.operations.submitRetry(retry); // Lost response/repeated delivery cannot launch another attempt.
            assertEquals(2, fixture.starts.get());
            assertFailure(409, "RETRY_SUPERSEDED", () -> fixture.operations.submitRetry(staleSibling));
            assertFailure(409, "RETRY_NOT_AVAILABLE", () -> fixture.operations.reserveRetry(creation, creation));
            String deliberate = (String) fixture.operations.reserveRetry(creation, retry).get("operationId");
            fixture.operations.submitRetry(deliberate);
            await(fixture.operations, deliberate, state("OPENING"));
            assertEquals(3, fixture.starts.get());
            assertEquals("preserve existing catalog", Files.readString(root.resolve("catalog.db")));
            assertFalse(Files.exists(directory.resolve("Kept (001)")));
        }
    }

    @Test void retryRevalidatesAndNeverRecreatesMissingSession() throws Exception {
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String creation = fixture.reserve();
            fixture.operations.submit(creation, input("Moved"));
            var created = await(fixture.operations, creation, state("OPENING"));
            fixture.failed(false);
            await(fixture.operations, creation, state("OPEN_FAILED"));
            Path root = Path.of((String) created.get("path"));
            Files.move(root, directory.resolve("elsewhere"));
            String retry = (String) fixture.operations.reserveRetry(creation, creation).get("operationId");
            fixture.operations.submitRetry(retry);
            await(fixture.operations, retry, state("OPEN_FAILED"));
            assertEquals(1, fixture.starts.get());
            assertFalse(Files.exists(root));
            assertTrue(Files.exists(directory.resolve("elsewhere/session.json")));
        }
    }

    @Test void invalidNameAndUnavailableParentAreRejectedBeforeMutation() throws Exception {
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String invalid = fixture.reserve();
            fixture.operations.submit(invalid, input("CON.txt"));
            assertEquals("INVALID_INPUT", await(fixture.operations, invalid, state("REJECTED")).get("errorCode"));
            String missing = fixture.reserve();
            fixture.operations.submit(missing, new SessionCreationOperations.Input(directory.resolve("missing").toString(), "Name"));
            assertEquals("PARENT_UNAVAILABLE", await(fixture.operations, missing, state("REJECTED")).get("errorCode"));
            assertEquals(0, fixture.starts.get());
            assertFalse(Files.exists(directory.resolve("missing")));
        }
    }

    @Test void unsupportedStorageHasAnExplicitBoundedRejection() throws Exception {
        var host = mock(HostFileSystem.class);
        when(host.profile(any())).thenReturn(FileSystemProfile.EXFAT);
        try (var hosts = mockStatic(HostFileSystems.class)) {
            hosts.when(HostFileSystems::current).thenReturn(host);
            assertFailure(422, "UNSUPPORTED_STORAGE", () -> SessionCreationActions.create(input("Name")));
        }
        assertFalse(Files.exists(directory.resolve("Name")));
    }

    @Test void partialCreationReportsAttemptedPathAndPreservesDirectoryWithoutLaunchingOrRetrying() throws Exception {
        try (var fixture = new Fixture(input -> {
            Path manifest = Path.of(input.parentPath()).toRealPath().resolve(input.name()).resolve("session.json");
            try (var files = mockStatic(Files.class, invocation -> {
                if (invocation.getMethod().getName().equals("writeString") && manifest.equals(invocation.getArgument(0))) {
                    throw new AccessDeniedException("private native diagnostic");
                }
                return invocation.callRealMethod();
            })) {
                return new SessionCreationService().create(Path.of(input.parentPath()), input.name());
            }
        })) {
            String id = fixture.reserve();
            fixture.operations.submit(id, input("Partial"));
            var result = await(fixture.operations, id, state("CREATION_UNCERTAIN"));
            assertEquals(directory.toRealPath().resolve("Partial").toString(), result.get("attemptedPath"));
            assertEquals(true, result.get("directoryCreated"));
            assertEquals(false, result.get("sessionCreated"));
            assertFalse(result.toString().contains("private native diagnostic"));
            assertTrue(Files.isDirectory(directory.resolve("Partial")));
            assertFalse(Files.exists(directory.resolve("Partial (001)")));
            assertEquals(0, fixture.starts.get());
            fixture.operations.submit(id, input("Partial"));
            assertFalse(Files.exists(directory.resolve("Partial (001)")));
        }
    }

    @Test void expiredUnknownAndPreviousRuntimeHandlesNeverExecuteAndStoreIsBounded() throws Exception {
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String first = fixture.reserve();
            for (int index = 1; index < 64; index++) fixture.reserve();
            assertFailure(429, "OPERATION_STORE_FULL", fixture.operations::reserve);
            String runtime = (String) fixture.operations.list().get("runtimeId");
            assertFailure(410, "OPERATION_EXPIRED", () -> fixture.operations.submit(runtime + "." + UUID.randomUUID(), input("Never")));
            assertFailure(409, "MANAGEMENT_RUNTIME_CHANGED", () -> fixture.operations.submit(UUID.randomUUID() + "." + UUID.randomUUID(), input("Never")));
            fixture.ticks.addAndGet(Duration.ofMinutes(11).toNanos());
            assertFailure(410, "OPERATION_EXPIRED", () -> fixture.operations.submit(first, input("Never")));
            assertEquals(0, ((List<?>) fixture.operations.list().get("operations")).size());
            assertEquals(0, fixture.starts.get());
            assertFalse(Files.exists(directory.resolve("Never")));
        }
    }

    @Test void activeCreationDoesNotExpireAndUncooperativeShutdownRetainsAdmissionUntilActualCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interruptions = new AtomicInteger();
        var fixture = new Fixture(input -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { interruptions.incrementAndGet(); }
            }
            return SessionCreationActions.create(input);
        });
        try {
            String id = fixture.reserve();
            fixture.operations.submit(id, input("During shutdown"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            fixture.ticks.addAndGet(Duration.ofHours(1).toNanos());
            assertEquals("CREATING", fixture.operations.status(id).get("state"));
            assertThrows(IllegalStateException.class, () -> fixture.operations.shutdown(Duration.ofMillis(20)));
            assertEquals("CREATING", fixture.operations.status(id).get("state"));
            assertEquals(true, fixture.operations.list().get("stopping"));
            assertFailure(503, "MANAGEMENT_STOPPING", fixture.admission::acquire);
            assertFailure(503, "MANAGEMENT_STOPPING", fixture.operations::reserve);
            release.countDown();
            var stopped = await(fixture.operations, id, state("OPEN_FAILED"));
            assertTrue(interruptions.get() > 0);
            assertEquals(true, stopped.get("sessionCreated"));
            assertEquals(0, fixture.starts.get());
            assertTrue(Files.exists(directory.resolve("During shutdown/session.json")));
        } finally { release.countDown(); fixture.close(); }
    }

    @Test void activeAndFailedLiveRetryPinOriginalCreationAcrossRetentionAndExpireOnlyAfterRelease() throws Exception {
        try (var fixture = new Fixture(SessionCreationActions::create)) {
            String creation = fixture.reserve();
            fixture.operations.submit(creation, input("Retained"));
            await(fixture.operations, creation, state("OPENING"));
            fixture.failed(false);
            await(fixture.operations, creation, state("OPEN_FAILED"));
            String retry = (String) fixture.operations.reserveRetry(creation, creation).get("operationId");
            fixture.operations.submitRetry(retry);
            await(fixture.operations, retry, state("OPENING"));
            fixture.ticks.addAndGet(Duration.ofMinutes(11).toNanos());
            assertEquals(retry, fixture.operations.status(creation).get("latestAttemptId"));
            fixture.failed(true);
            await(fixture.operations, retry, state("OPEN_FAILED"));
            fixture.ticks.addAndGet(Duration.ofMinutes(11).toNanos());
            assertEquals(retry, fixture.operations.status(creation).get("latestAttemptId"));
            fixture.failed(false);
            assertFailure(410, "OPERATION_EXPIRED", () -> fixture.operations.status(retry));
            assertFailure(410, "OPERATION_EXPIRED", () -> fixture.operations.status(creation));
            assertEquals(0, ((List<?>) fixture.operations.list().get("operations")).size());
        }
    }

    @Test void interruptedCreationKeepsAnUncertainObservationAndNeverStartsCatalog() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (var fixture = new Fixture(input -> {
            entered.countDown();
            waitFor(new CountDownLatch(1));
            throw new AssertionError("Unreachable");
        })) {
            String id = fixture.reserve();
            fixture.operations.submit(id, input("Interrupted"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            fixture.operations.shutdown(Duration.ofSeconds(2));
            assertEquals("CREATION_UNCERTAIN", fixture.operations.status(id).get("state"));
            assertEquals(0, fixture.starts.get());
        }
    }

    private SessionCreationOperations.Input input(String name) {
        return new SessionCreationOperations.Input(directory.toAbsolutePath().toString(), name);
    }

    private static void waitFor(CountDownLatch latch) throws IOException {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", interrupted); }
    }

    private static Predicate<Map<String, Object>> state(String state) {
        return result -> state.equals(result.get("state")) && !Boolean.TRUE.equals(result.get("executionActive"))
                && (!state.equals("OPENING") || result.get("catalogId") != null);
    }

    private static Map<String, Object> await(SessionCreationOperations operations, String id,
            Predicate<Map<String, Object>> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Map<String, Object> result;
        do {
            result = operations.status(id);
            if (condition.test(result)) return result;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Operation did not reach expected state: " + result);
    }

    private static void assertFailure(int status, String code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(SessionCreationOperations.Failure.class, action);
        assertEquals(status, failure.status);
        assertEquals(code, failure.code);
    }

    private static final class Fixture implements AutoCloseable {
        final CatalogProcessSupervisor catalog = mock(CatalogProcessSupervisor.class);
        final AtomicReference<CatalogStatus> status = new AtomicReference<>(new CatalogStatus(CatalogStatus.State.NOT_STARTED, false, null, null, null));
        final AtomicReference<String> generation = new AtomicReference<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicLong ticks = new AtomicLong();
        final SessionLifecycleAdmission admission = new SessionLifecycleAdmission(catalog);
        final SessionCreationOperations operations;

        Fixture(SessionCreationOperations.Creator creator) {
            when(catalog.status()).thenAnswer(invocation -> status.get());
            when(catalog.catalogId()).thenAnswer(invocation -> generation.get());
            when(catalog.start(any())).thenAnswer(invocation -> {
                starts.incrementAndGet(); generation.set(UUID.randomUUID().toString());
                status.set(new CatalogStatus(CatalogStatus.State.CREATED, true, null, null, null));
                return status.get();
            });
            operations = new SessionCreationOperations(catalog, admission, creator, ticks::get, Duration.ofMinutes(10));
        }
        String reserve() { return (String) operations.reserve().get("operationId"); }
        void running() { status.set(new CatalogStatus(CatalogStatus.State.RUNNING, true, 12345, null, null)); }
        void failed(boolean alive) { status.set(new CatalogStatus(CatalogStatus.State.FAILED, alive, null, alive ? null : 1, CatalogStatus.Failure.INITIALIZATION_FAILED)); }
        @Override public void close() { operations.shutdown(Duration.ofSeconds(3)); }
    }
}
