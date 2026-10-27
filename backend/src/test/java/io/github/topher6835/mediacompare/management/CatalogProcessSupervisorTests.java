package io.github.topher6835.mediacompare.management;

import static io.github.topher6835.mediacompare.management.CatalogStatus.State.*;
import static io.github.topher6835.mediacompare.management.CatalogStatus.Failure.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogProcessSupervisorTests {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void realCatalogReadinessOwnershipAndOrderlyExitPermitLaterStart() throws Exception {
        Session session = Session.create(directory.resolve("Session with spaces café"));
        try (var catalog = realCatalog()) {
            assertEquals(NOT_STARTED, catalog.status().state());
            catalog.start(session.root());
            assertThrows(IllegalStateException.class, () -> catalog.start(session.root()));
            CatalogStatus running = await(catalog, s -> s.state() == RUNNING);
            assertTrue(running.processAlive());
            assertNotNull(running.port());
            var response = client.send(HttpRequest.newBuilder(java.net.URI.create(
                    "http://127.0.0.1:" + running.port() + "/api/health"))
                    .header(ManagedCatalogSecurity.HEADER, catalog.route(catalog.catalogId()).credential()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
            assertTrue(Files.isRegularFile(session.catalogPath()));
            assertThrows(IllegalStateException.class, () -> CatalogOwnership.acquire(session.catalogJdbcUrl()));
            catalog.requestStop();
            CatalogStatus stopped = await(catalog, s -> !s.processAlive());
            assertEquals(NOT_STARTED, stopped.state());
            assertEquals(0, stopped.exitCode());
            assertNull(stopped.port());
            try (var ownership = CatalogOwnership.acquire(session.catalogJdbcUrl())) {
                assertNotNull(ownership.lockPath());
            }
            catalog.start(session.root());
            await(catalog, s -> s.state() == RUNNING);
        }
    }

    @Test
    void invalidSessionLeavesManagementAvailableAndCanBeFollowedByAValidStart() throws Exception {
        try (var management = ManagementServer.start(frontendBuild(), 0, realCatalog())) {
            var catalog = management.catalog();
            catalog.start(directory.resolve("missing-private-session"));
            await(catalog, s -> !s.processAlive() && s.failure() == INVALID_SESSION);
            var response = get(management, "/api/management/status");
            assertTrue(response.body().contains("\"catalogState\":\"FAILED\""));
            assertTrue(response.body().contains("\"processAlive\":false"));
            assertFalse(response.body().contains("missing-private-session"));
            assertEquals(200, get(management, "/").statusCode());
            assertEquals(403, client.send(HttpRequest.newBuilder(management.baseUri().resolve("/api/management/start"))
                    .POST(HttpRequest.BodyPublishers.ofString("private path")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            catalog.start(Session.create(directory.resolve("valid")).root());
            await(catalog, s -> s.state() == RUNNING);
            assertEquals(200, get(management, "/api/management/status").statusCode());
        }
    }

    @Test
    void catalogOwnershipConflictIsReportedWithoutReleasingTheExistingOwner() throws Exception {
        Session session = Session.create(directory.resolve("owned"));
        try (var ownership = CatalogOwnership.acquire(session.catalogJdbcUrl()); var catalog = realCatalog()) {
            catalog.start(session.root());
            await(catalog, s -> !s.processAlive() && s.failure() == OWNERSHIP_FAILED);
            assertThrows(IllegalStateException.class, () -> CatalogOwnership.acquire(session.catalogJdbcUrl()));
            assertNotNull(ownership.lockPath());
        }
    }

    @Test
    void invalidSqliteCatalogFailsInitializationWithoutBecomingReady() throws Exception {
        Session session = Session.create(directory.resolve("corrupt"));
        Files.writeString(session.catalogPath(), "not a SQLite database");
        try (var catalog = realCatalog()) {
            catalog.start(session.root());
            CatalogStatus failed = await(catalog, s -> !s.processAlive() && s.failure() == INITIALIZATION_FAILED);
            assertNull(failed.port());
            assertNotEquals(0, failed.exitCode());
        }
    }

    @Test
    void concurrentStartsAdmitOnlyOneChild() throws Exception {
        Path root = fixtureRoot("initializing");
        try (var catalog = fixtureCatalog(Duration.ofSeconds(5), Duration.ofSeconds(2))) {
            var gate = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> startAfter(gate, catalog, root));
            var second = CompletableFuture.supplyAsync(() -> startAfter(gate, catalog, root));
            gate.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            await(catalog, s -> s.state() == STARTING);
            catalog.requestStop(); // Stop during initialization never announces RUNNING.
            assertEquals(NOT_STARTED, await(catalog, s -> !s.processAlive()).state());
        }
    }

    @Test
    void unrelatedReadinessTokenCannotMarkTheChildReadyAndStartupTimeoutCleansUp() throws Exception {
        try (var catalog = fixtureCatalog(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            catalog.start(fixtureRoot("wrong-token"));
            CatalogStatus failed = await(catalog, s -> !s.processAlive());
            assertEquals(FAILED, failed.state());
            assertEquals(STARTUP_TIMEOUT, failed.failure());
            assertNull(failed.port());
        }
    }

    @Test
    void unexpectedChildExitIsObservedWhileManagementStaysAvailable() throws Exception {
        Path root = fixtureRoot("unexpected-exit");
        try (var management = ManagementServer.start(frontendBuild(), 0, fixtureCatalog(Duration.ofSeconds(5), Duration.ofSeconds(2)))) {
            var catalog = management.catalog();
            try {
                catalog.start(root);
                await(catalog, s -> s.state() == RUNNING);
                Files.createFile(root.resolve("release"));
                CatalogStatus failed = await(catalog, s -> !s.processAlive());
                assertEquals(UNEXPECTED_EXIT, failed.failure());
                assertEquals(17, failed.exitCode());
                assertEquals(200, get(management, "/api/management/status").statusCode());
            } finally {
                if (!Files.exists(root.resolve("release"))) Files.createFile(root.resolve("release"));
            }
        }
    }

    @Test
    void stalledShutdownBlocksStartAndManagementCloseUntilActualProcessExit() throws Exception {
        Path root = fixtureRoot("stalled-cleanup");
        var management = ManagementServer.start(frontendBuild(), 0, fixtureCatalog(Duration.ofSeconds(5), Duration.ofMillis(200)));
        var catalog = management.catalog();
        try {
            catalog.start(root);
            await(catalog, s -> s.state() == RUNNING);
            assertEquals(STOPPING, catalog.requestStop().state());
            assertThrows(IllegalStateException.class, () -> catalog.start(root));
            CatalogStatus failed = await(catalog, s -> s.failure() == SHUTDOWN_TIMEOUT);
            assertTrue(failed.processAlive());
            assertThrows(IllegalStateException.class, () -> catalog.start(root));
            assertThrows(IllegalStateException.class, management::close);
            assertTrue(get(management, "/api/management/status").body().contains("\"processAlive\":true"));
        } finally {
            Files.createFile(root.resolve("release"));
            await(catalog, s -> !s.processAlive());
            management.close();
        }
        assertFalse(catalog.status().processAlive());
        assertThrows(IllegalStateException.class, () -> catalog.start(root));
    }

    @Test
    void failedProcessCreationDoesNotClaimAnActiveCatalog() throws Exception {
        try (var catalog = new CatalogProcessSupervisor(List.of(directory.resolve("missing-java").toString()),
                Duration.ofSeconds(2), Duration.ofSeconds(2))) {
            CatalogStatus status = catalog.start(directory);
            assertEquals(PROCESS_START_FAILED, status.failure());
            assertFalse(status.processAlive());
        }
    }

    @Test
    void cleanupFailureIsReportedSeparatelyFromInitializationFailure() throws Exception {
        try (var catalog = fixtureCatalog(Duration.ofSeconds(5), Duration.ofSeconds(2))) {
            catalog.start(fixtureRoot("failed-cleanup"));
            await(catalog, s -> s.state() == RUNNING);
            catalog.requestStop();
            CatalogStatus failed = await(catalog, s -> !s.processAlive() && s.failure() == SHUTDOWN_FAILED);
            assertEquals(FAILED, failed.state());
            assertNotEquals(0, failed.exitCode());
        }
    }

    @Test
    void controlPipeEofRequestsRealCatalogCleanupWithoutAStopCommand() throws Exception {
        Session session = Session.create(directory.resolve("parent lost"));
        String token = java.util.UUID.randomUUID().toString();
        var command = new java.util.ArrayList<>(CatalogChildCommand.currentApplication());
        command.addAll(List.of("catalog-child", token, session.root().toString()));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        var ready = new CompletableFuture<Void>();
        Thread output = new Thread(() -> {
            try (var reader = child.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("MC_CATALOG " + token + " READY ")) ready.complete(null);
                }
            } catch (java.io.IOException exception) { ready.completeExceptionally(exception); }
        });
        output.setDaemon(true);
        output.start();
        try {
            ready.get(25, TimeUnit.SECONDS);
            child.getOutputStream().close(); // Equivalent to the parent disappearing and closing its pipe.
            assertTrue(child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            try (var ownership = CatalogOwnership.acquire(session.catalogJdbcUrl())) {
                assertNotNull(ownership.lockPath());
            }
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(15, TimeUnit.SECONDS)) child.destroyForcibly(); // Failed-test cleanup only.
        }
    }

    private CatalogProcessSupervisor realCatalog() {
        return new CatalogProcessSupervisor(CatalogChildCommand.currentApplication(), Duration.ofSeconds(25), Duration.ofSeconds(15));
    }

    private CatalogProcessSupervisor fixtureCatalog(Duration startup, Duration shutdown) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new CatalogProcessSupervisor(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), CatalogProcessFixture.class.getName()), startup, shutdown);
    }

    private Path fixtureRoot(String mode) throws Exception { return Files.createDirectory(directory.resolve(mode)); }

    private Path frontendBuild() throws Exception {
        Path build = Files.createDirectory(directory.resolve("frontend"));
        Files.writeString(build.resolve("index.html"), "<html>React entry</html>");
        return build;
    }

    private HttpResponse<String> get(ManagementServer management, String path) throws Exception {
        return client.send(ManagementTestClient.authorized(management, path).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static boolean startAfter(CountDownLatch gate, CatalogProcessSupervisor catalog, Path root) {
        try { gate.await(); catalog.start(root); return true; }
        catch (IllegalStateException expected) { return false; }
        catch (InterruptedException exception) { throw new IllegalStateException(exception); }
    }

    private CatalogStatus await(CatalogProcessSupervisor catalog, Predicate<CatalogStatus> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        CatalogStatus status;
        do {
            status = catalog.status();
            if (condition.test(status)) return status;
            Thread.sleep(10); // Bounded state polling, not a fixed startup/shutdown delay.
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Catalog state did not arrive: " + status);
    }
}
