package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.Socket;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import io.github.topher6835.mediacompare.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SessionCreationApiTests {
    @TempDir Path directory;
    private static final String API = SessionCreationApi.PREFIX;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void createsCollisionResolvedSessionAndOpensRealChildWithMatchingGenerationAndRecoverableResult() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("Sessions"));
        Files.writeString(parent.resolve("Café 日本 + # %"), "original media");
        try (var server = server(realCatalog(), null)) {
            String id = reserve(server);
            assertFalse(Files.exists(parent.resolve("Café 日本 + # % (001)")));
            assertTrue(get(server, API).body().contains(id)); // Refresh/lost reservation-response discovery.
            String body = creationForm(parent, "Café 日本 + # %");
            assertEquals(202, post(server, API + "/" + id, body).statusCode());
            JsonNode result = await(server, id, node -> node.get("state").stringValue().equals("RUNNING"));
            Path path = parent.toRealPath().resolve("Café 日本 + # % (001)");
            assertEquals(path.toString(), result.get("path").stringValue());
            assertEquals(path.getFileName().toString(), result.get("name").stringValue());
            assertTrue(result.get("sessionCreated").booleanValue());
            assertTrue(result.get("processAlive").booleanValue());
            String generation = result.get("catalogId").stringValue();
            assertEquals(generation, json.readTree(get(server, "/api/management/status").body()).get("catalogId").stringValue());
            assertEquals(generation, json.readTree(post(server, API + "/" + id, body).body()).get("catalogId").stringValue());
            assertEquals(409, post(server, API + "/" + id, creationForm(parent, "Changed")).statusCode());
            assertEquals(200, send(ManagementTestClient.authorized(server, "/api/health")
                    .header(LocalApiSecurity.CATALOG_HEADER, generation)).statusCode());
            assertTrue(Files.isRegularFile(path.resolve("catalog.db"))); // Only the supervised child creates it.
            assertEquals("original media", Files.readString(parent.resolve("Café 日本 + # %")));
            assertFalse(Files.exists(parent.resolve("Café 日本 + # % (002)")));
            String another = reserve(server);
            assertEquals(409, post(server, API + "/" + another, creationForm(parent, "While alive")).statusCode());
            assertFalse(Files.exists(parent.resolve("While alive")));
            assertEquals(409, open(server, path).statusCode());
        }
    }

    @Test void failedProcessStartRetainsCreatedSessionAndRetriesOnlyExistingPathOncePerAttempt() throws Exception {
        var catalog = new CatalogProcessSupervisor(List.of(directory.resolve("missing-java").toString()),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        Path parent = Files.createDirectory(directory.resolve("Sessions"));
        try (var server = server(catalog, null)) {
            String id = reserve(server);
            post(server, API + "/" + id, creationForm(parent, "Kept"));
            var failed = await(server, id, node -> node.get("state").stringValue().equals("OPEN_FAILED"));
            Path path = Path.of(failed.get("path").stringValue());
            String generation = failed.get("catalogId").stringValue();
            assertEquals("PROCESS_START_FAILED", failed.get("catalogFailureCode").stringValue());
            assertFalse(failed.get("processAlive").booleanValue());
            Files.writeString(path.resolve("notes.txt"), "keep");
            String retry = json.readTree(post(server, API + "/" + id + "/retry-open", form("failedAttemptId", id)).body())
                    .get("operationId").stringValue();
            assertEquals(202, post(server, API + "/" + retry + "/open", null).statusCode());
            var retryFailed = await(server, retry, node -> node.get("state").stringValue().equals("OPEN_FAILED"));
            assertEquals(path.toString(), retryFailed.get("path").stringValue());
            String retryGeneration = retryFailed.get("catalogId").stringValue();
            assertNotEquals(generation, retryGeneration);
            var original = json.readTree(get(server, API + "/" + id).body());
            assertEquals("PROCESS_START_FAILED", original.get("openingFailureCode").stringValue());
            assertFalse(original.get("catalogMatchesCurrent").booleanValue());
            assertTrue(original.get("catalogFailureCode").isNull());
            assertEquals(retryGeneration, json.readTree(post(server, API + "/" + retry + "/open", null).body()).get("catalogId").stringValue());
            assertEquals(409, post(server, API + "/" + id + "/retry-open", form("failedAttemptId", id)).statusCode());
            assertEquals("keep", Files.readString(path.resolve("notes.txt")));
            assertTrue(Files.exists(path.resolve("session.json")));
            assertFalse(Files.exists(parent.resolve("Kept (001)")));
        }
    }

    @Test void failedRealCatalogInitializationPreservesCreatedSessionAndCanBeRecoveredWithExplicitRetry() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("Sessions"));
        try (var server = server(realCatalog(), input -> {
            var created = SessionCreationActions.create(input);
            Files.writeString(created.session().catalogPath(), "corrupt catalog for controlled startup failure");
            return created;
        })) {
            String id = reserve(server);
            post(server, API + "/" + id, creationForm(parent, "Bad catalog"));
            var failed = await(server, id, node -> node.get("state").stringValue().equals("OPEN_FAILED")
                    && !node.get("processAlive").booleanValue());
            assertEquals("INITIALIZATION_FAILED", failed.get("catalogFailureCode").stringValue());
            Path root = Path.of(failed.get("path").stringValue());
            assertEquals("corrupt catalog for controlled startup failure", Files.readString(root.resolve("catalog.db")));
            assertEquals(root, Session.open(root).root());
            // Explicit test-only repair of this disposable database; production never removes it on failure.
            Files.delete(root.resolve("catalog.db"));
            String retry = json.readTree(post(server, API + "/" + id + "/retry-open", form("failedAttemptId", id)).body())
                    .get("operationId").stringValue();
            post(server, API + "/" + retry + "/open", null);
            var ready = await(server, retry, node -> node.get("state").stringValue().equals("RUNNING"));
            assertEquals(root.toString(), ready.get("path").stringValue());
            assertEquals(server.catalog().catalogId(), ready.get("catalogId").stringValue());
            assertFalse(Files.exists(parent.resolve("Bad catalog (001)")));
        }
    }

    @Test void slowCreationKeepsStatusResponsiveAndExcludesHttpOpenAndNew() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Session existing = Session.create(directory.resolve("Existing"));
        var catalog = mock(CatalogProcessSupervisor.class);
        when(catalog.status()).thenReturn(new CatalogStatus(CatalogStatus.State.NOT_STARTED, false, null, null, null));
        try (var server = server(catalog, input -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException("Interrupted", interrupted); }
            return SessionCreationActions.create(input);
        })) {
            try {
                String id = reserve(server);
                assertEquals(202, post(server, API + "/" + id, creationForm(directory, "Creating")).statusCode());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(200, get(server, "/api/management/status").statusCode());
                assertEquals("CREATING", json.readTree(get(server, API + "/" + id).body()).get("state").stringValue());
                assertEquals(409, open(server, existing.root()).statusCode());
                String competing = reserve(server);
                assertEquals(409, post(server, API + "/" + competing, creationForm(directory, "Competing")).statusCode());
                assertFalse(Files.exists(directory.resolve("Competing")));
                assertFalse(Files.exists(existing.catalogPath()));
                verify(catalog, never()).start(any());
            } finally { release.countDown(); }
        }
    }

    @Test void capabilityHostOriginAndFetchMetadataChecksProtectAllCreationMutations() throws Exception {
        try (var server = server(new CatalogProcessSupervisor(), null)) {
            String id = reserve(server);
            for (String suffix : List.of("", "/" + id, "/" + id + "/retry-open", "/" + id + "/open")) {
                String path = API + suffix;
                assertEquals(403, send(HttpRequest.newBuilder(server.baseUri().resolve(path)).POST(HttpRequest.BodyPublishers.noBody())).statusCode());
                assertEquals(403, send(ManagementTestClient.authorized(server, path).header("Origin", "https://evil.example")
                        .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
                assertEquals(403, send(ManagementTestClient.authorized(server, path).header("Sec-Fetch-Site", "cross-site")
                        .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
                var preflight = send(ManagementTestClient.authorized(server, path).header("Origin", "https://evil.example")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
                assertEquals(403, preflight.statusCode());
                assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
            try (var socket = new Socket("127.0.0.1", server.baseUri().getPort())) {
                socket.setSoTimeout(3000);
                String raw = "POST " + API + " HTTP/1.1\r\nHost: localhost:" + server.baseUri().getPort()
                        + "\r\n" + LocalApiSecurity.ACCESS_HEADER + ": " + ManagementTestClient.capability(server)
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
                assertTrue(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
            }
            assertEquals(405, get(server, API + "/" + id + "/retry-open").statusCode());
            assertEquals(405, get(server, API + "/" + id + "/open").statusCode());
            assertEquals("no-store", get(server, API).headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("RESERVED", json.readTree(get(server, API + "/" + id).body()).get("state").stringValue());
            assertFalse(server.catalog().status().processAlive());
        }
    }

    @Test void malformedOversizedAndStaleRequestsNeverCreateDataOrRevealDiagnostics() throws Exception {
        try (var server = server(new CatalogProcessSupervisor(), null)) {
            String id = reserve(server);
            assertEquals(400, post(server, API, "unexpected").statusCode());
            assertEquals(413, post(server, API + "/" + id, "a".repeat(65_537)).statusCode());
            for (String body : List.of("parentPath=%FF&name=Name", "parentPath=%C0%80&name=Name", "parentPath=x&name=y&name=z",
                    "parentPath=x&name=y&extra=z", "parentPath=x&name=%", "parentPath=x")) {
                assertEquals(400, post(server, API + "/" + id, body).statusCode(), body);
            }
            assertEquals(415, send(ManagementTestClient.authorized(server, API + "/" + id)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            String runtime = json.readTree(get(server, API).body()).get("runtimeId").stringValue();
            assertEquals(410, post(server, API + "/" + runtime + "." + UUID.randomUUID(), creationForm(directory, "Never")).statusCode());
            var stale = post(server, API + "/" + UUID.randomUUID() + "." + UUID.randomUUID(), creationForm(directory, "Never"));
            assertEquals(409, stale.statusCode());
            assertTrue(stale.body().contains("MANAGEMENT_RUNTIME_CHANGED"));
            assertFalse(stale.body().contains(directory.toString()));
            assertFalse(Files.exists(directory.resolve("Never")));
        }
    }

    @Test void managementRestartRejectsOldHandleAndDoesNotReplayCreation() throws Exception {
        String old;
        try (var server = server(new CatalogProcessSupervisor(), null)) { old = reserve(server); }
        try (var restarted = server(new CatalogProcessSupervisor(), null)) {
            assertEquals(409, post(restarted, API + "/" + old, creationForm(directory, "Never replay")).statusCode());
            assertEquals(0, json.readTree(get(restarted, API).body()).get("operations").size());
            assertFalse(Files.exists(directory.resolve("Never replay")));
        }
    }

    private ManagementServer server(CatalogProcessSupervisor catalog, SessionCreationOperations.Creator creator) throws Exception {
        Path build = Files.createTempDirectory(directory, "frontend-");
        Files.writeString(build.resolve("index.html"), "<html>React</html>");
        return ManagementServer.start(build, 0, catalog, null, creator);
    }

    private CatalogProcessSupervisor realCatalog() {
        return new CatalogProcessSupervisor(CatalogChildCommand.currentApplication(), Duration.ofSeconds(25), Duration.ofSeconds(15));
    }

    private String reserve(ManagementServer server) throws Exception {
        var response = post(server, API, null);
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body()).get("operationId").stringValue();
    }

    private HttpResponse<String> open(ManagementServer server, Path path) throws Exception {
        return send(ManagementTestClient.authorized(server, "/api/management/open").header("Content-Type", "text/plain;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(path.toString())));
    }

    private HttpResponse<String> get(ManagementServer server, String path) throws Exception { return send(ManagementTestClient.authorized(server, path)); }

    private HttpResponse<String> post(ManagementServer server, String path, String body) throws Exception {
        var request = ManagementTestClient.authorized(server, path);
        if (body != null) request.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        return send(request.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode await(ManagementServer server, String id, Predicate<JsonNode> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        JsonNode status;
        do {
            status = json.readTree(get(server, API + "/" + id).body());
            if (!status.get("executionActive").booleanValue() && condition.test(status)) return status;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Operation did not reach expected state: " + status);
    }

    private static String creationForm(Path parent, String name) { return form("parentPath", parent.toString()) + "&" + form("name", name); }
    private static String form(String key, String value) { return key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
