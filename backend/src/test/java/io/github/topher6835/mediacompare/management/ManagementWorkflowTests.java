package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Predicate;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ManagementWorkflowTests {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void operationSubmissionPollingReplayAndRuntimeIsolationWorkThroughManagement() throws Exception {
        Session session = Session.create(directory.resolve("operations"));
        try (var server = server()) {
            assertEquals(202, open(server, session.root()).statusCode());
            await(server, s -> s.state() == CatalogStatus.State.RUNNING);
            String generation = server.catalog().catalogId();
            assertEquals(403, send(HttpRequest.newBuilder(server.baseUri().resolve("/api/catalog-operations"))
                    .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            var reservation = operationPost(server, "", generation, null);
            assertEquals(201, reservation.statusCode());
            String id = json.readTree(reservation.body()).get("operationId").stringValue();
            var runtime = json.readTree(forwarded(server, "/api/catalog-operations", generation).body());
            assertTrue(id.startsWith(runtime.get("runtimeId").stringValue() + "."));
            String command = "{\"kind\":\"CLEANUP_PREFLIGHT\",\"digestHex\":\"" + "0".repeat(64)
                    + "\",\"preflight\":{\"keeperFileEntryId\":1,\"candidateFileEntryIds\":[2]}}";
            assertEquals(202, operationPost(server, "/" + id, generation, command).statusCode());
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            tools.jackson.databind.JsonNode result;
            do {
                result = json.readTree(forwarded(server, "/api/catalog-operations/" + id, generation).body());
                if (result.get("status").stringValue().equals("SUCCEEDED")) break;
                Thread.sleep(5);
            } while (System.nanoTime() < end);
            assertEquals("SUCCEEDED", result.get("status").stringValue());
            assertEquals("BLOCKED", result.get("result").get("status").stringValue());
            assertEquals("GROUP_CHANGED", result.get("result").get("reason").stringValue());
            assertEquals("SUCCEEDED", json.readTree(operationPost(server, "/" + id, generation, command).body())
                    .get("status").stringValue());
            assertEquals(409, forwarded(server, "/api/catalog-operations/" + id, "stale").statusCode());
            assertEquals(200, operationPost(server, "/" + id + "/cancel", generation, null).statusCode());
            server.catalog().requestStop();
            await(server, s -> !s.processAlive());
            assertEquals(202, open(server, session.root()).statusCode());
            await(server, s -> s.state() == CatalogStatus.State.RUNNING);
            assertEquals(409, forwarded(server, "/api/catalog-operations/" + id, server.catalog().catalogId()).statusCode());
        }
    }

    private HttpResponse<String> operationPost(ManagementServer server, String suffix, String generation, String body) throws Exception {
        var request = ManagementTestClient.authorized(server, "/api/catalog-operations" + suffix)
                .header(LocalApiSecurity.CATALOG_HEADER, generation);
        if (body != null) request.header("Content-Type", "application/json");
        return send(request.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)));
    }

    @Test void browserPathEntryOpeningRefreshAndForwardingStayOnTheSelectedGeneration() throws Exception {
        Session first = Session.create(directory.resolve("Session café 日本 + # % spaces"));
        Session second = Session.create(directory.resolve("Second"));
        try (var server = server()) {
            String encoded = URLEncoder.encode(first.root().toString(), StandardCharsets.UTF_8);
            var listing = send(ManagementTestClient.authorized(server, "/api/management/filesystem/directories?path=" + encoded));
            assertEquals(200, listing.statusCode());
            assertEquals(first.root().toString(), json.readTree(listing.body()).get("path").stringValue());
            assertTrue(send(ManagementTestClient.authorized(server, "/api/management/filesystem/validate?path=" + encoded))
                    .body().contains("VALID_SESSION"));
            assertFalse(Files.exists(first.catalogPath()));
            assertEquals(202, open(server, first.root()).statusCode());
            await(server, status -> status.state() == CatalogStatus.State.RUNNING);
            String firstId = server.catalog().catalogId();
            var route = server.catalog().route(firstId);
            assertEquals(409, open(server, second.root()).statusCode());
            assertEquals(200, forwarded(server, "/api/health", firstId).statusCode());
            assertEquals("ok", forwarded(server, "/api/health", firstId).body());
            assertEquals("[]", forwarded(server, "/api/sources", firstId).body());
            assertEquals(400, send(ManagementTestClient.authorized(server, "/api/media-library/items/0/reveal")
                    .header(LocalApiSecurity.CATALOG_HEADER, firstId).POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            var action = send(ManagementTestClient.authorized(server, "/api/media-library/items/0/reveal")
                    .header(LocalApiSecurity.CATALOG_HEADER, firstId).header("X-Media-Compare-Reveal", "1")
                    .POST(HttpRequest.BodyPublishers.noBody()));
            var directAction = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + route.port() + "/api/media-library/items/0/reveal"))
                    .header(ManagedCatalogSecurity.HEADER, route.credential()).header("X-Media-Compare-Reveal", "1")
                    .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(directAction.statusCode(), action.statusCode(), action.body());
            assertEquals(404, action.statusCode(), action.body());
            assertEquals(409, forwarded(server, "/api/health", "stale").statusCode());
            assertEquals(409, send(ManagementTestClient.authorized(server, "/api/health")).statusCode());
            assertEquals(200, send(ManagementTestClient.authorized(server, "/api/management/status")).statusCode());
            var refreshed = json.readTree(send(ManagementTestClient.authorized(server, "/api/management/status")).body());
            assertEquals("RUNNING", refreshed.get("catalogState").stringValue());
            assertEquals(firstId, refreshed.get("catalogId").stringValue());
            assertFalse(refreshed.has("port"));
            assertFalse(refreshed.has("credential"));
            var direct = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + route.port() + "/api/health"));
            assertEquals(403, send(direct).statusCode());
            assertEquals(403, send(direct.header(ManagedCatalogSecurity.HEADER, firstId)).statusCode());
            assertEquals(403, send(direct.setHeader(ManagedCatalogSecurity.HEADER, route.credential())
                    .header("Origin", "https://evil.example")).statusCode());
            // Preview cookies still require an exact generation and do not authorize mutations.
            String access = ManagementTestClient.capability(server);
            assertEquals(409, send(HttpRequest.newBuilder(server.baseUri().resolve("/api/previews/missing?catalogId=stale"))
                    .header("Cookie", "mc_access=" + access)).statusCode());
            assertEquals(400, send(HttpRequest.newBuilder(server.baseUri().resolve("/api/previews/missing?catalogId=" + firstId))
                    .header("Cookie", "mc_access=" + access)).statusCode());
            assertEquals(403, send(HttpRequest.newBuilder(server.baseUri().resolve("/api/sources"))
                    .header("Cookie", "mc_access=" + access).POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
            server.catalog().requestStop();
            assertEquals(503, forwarded(server, "/api/health", firstId).statusCode());
            await(server, status -> !status.processAlive());
            assertEquals(202, open(server, second.root()).statusCode());
            await(server, status -> status.state() == CatalogStatus.State.RUNNING);
            assertNotEquals(firstId, server.catalog().catalogId());
            assertEquals(409, forwarded(server, "/api/health", firstId).statusCode());
            assertEquals(200, forwarded(server, "/api/health", server.catalog().catalogId()).statusCode());
        }
    }

    @Test void invalidSelectionsOwnershipConflictAndCorruptCatalogLeaveManagementUsable() throws Exception {
        Session owned = Session.create(directory.resolve("Owned"));
        Session corrupt = Session.create(directory.resolve("Corrupt"));
        Files.writeString(corrupt.catalogPath(), "not SQLite");
        try (var server = server()) {
            assertEquals(422, open(server, directory.resolve("missing")).statusCode());
            assertEquals(422, open(server, directory).statusCode());
            assertEquals(400, open(server, Path.of("relative")).statusCode());
            try (var ownership = CatalogOwnership.acquire(owned.catalogJdbcUrl())) {
                assertNotNull(ownership.lockPath());
                assertEquals(202, open(server, owned.root()).statusCode());
                await(server, status -> !status.processAlive() && status.failure() == CatalogStatus.Failure.OWNERSHIP_FAILED);
                assertEquals(200, send(ManagementTestClient.authorized(server, "/api/management/filesystem/roots")).statusCode());
                assertThrows(IllegalStateException.class, () -> CatalogOwnership.acquire(owned.catalogJdbcUrl()));
            }
            assertEquals(202, open(server, corrupt.root()).statusCode());
            await(server, status -> !status.processAlive() && status.failure() == CatalogStatus.Failure.INITIALIZATION_FAILED);
            String failure = send(ManagementTestClient.authorized(server, "/api/management/status")).body();
            assertTrue(failure.contains("INITIALIZATION_FAILED"));
            assertFalse(failure.contains(directory.toString()));
            assertEquals(200, send(HttpRequest.newBuilder(server.baseUri())).statusCode());
            assertEquals(202, open(server, owned.root()).statusCode());
            await(server, status -> status.state() == CatalogStatus.State.RUNNING);
            assertEquals(200, forwarded(server, "/api/health", server.catalog().catalogId()).statusCode());
        }
    }

    @Test void badFilesystemInputsAreBoundedAndNeverReturnFileContentsOrDiagnostics() throws Exception {
        Files.writeString(directory.resolve("private.txt"), "SECRET CONTENT");
        try (var server = server()) {
            for (String path : new String[] {"relative", directory.resolve("missing").toString(), directory.resolve("private.txt").toString()}) {
                var response = send(ManagementTestClient.authorized(server, "/api/management/filesystem/directories?path="
                        + URLEncoder.encode(path, StandardCharsets.UTF_8)));
                assertTrue(response.statusCode() >= 400);
                assertFalse(response.body().contains("SECRET CONTENT"));
                assertFalse(response.body().contains(directory.toString()));
            }
            assertEquals(400, send(ManagementTestClient.authorized(server, "/api/management/filesystem/directories?path=%00")).statusCode());
            assertEquals(400, send(ManagementTestClient.authorized(server, "/api/management/filesystem/directories?path=/&path=/tmp")).statusCode());
            String browsePath = "/api/management/filesystem/directories?path="
                    + URLEncoder.encode(directory.toString(), StandardCharsets.UTF_8) + "&filter=";
            assertEquals(200, send(ManagementTestClient.authorized(server, browsePath + "a".repeat(256))).statusCode());
            var oversizedFilter = send(ManagementTestClient.authorized(server, browsePath + "a".repeat(257)));
            assertEquals(400, oversizedFilter.statusCode());
            assertEquals("Folder name filter must be at most 256 characters.",
                    json.readTree(oversizedFilter.body()).get("message").stringValue());
            assertFalse(oversizedFilter.body().contains(directory.toString()));
            assertEquals(413, send(ManagementTestClient.authorized(server, "/api/management/open")
                    .header("Content-Type", "text/plain;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("a".repeat(65_537)))).statusCode());
        }
    }

    private ManagementServer server() throws Exception {
        Path build = Files.createDirectory(directory.resolve("frontend"));
        Files.writeString(build.resolve("index.html"), "<html>React</html>");
        return ManagementServer.start(build, 0, new CatalogProcessSupervisor(CatalogChildCommand.currentApplication(),
                Duration.ofSeconds(25), Duration.ofSeconds(15)));
    }
    private HttpResponse<String> open(ManagementServer server, Path path) throws Exception {
        return send(ManagementTestClient.authorized(server, "/api/management/open")
                .header("Content-Type", "text/plain;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(path.toString())));
    }
    private HttpResponse<String> forwarded(ManagementServer server, String path, String id) throws Exception {
        return send(ManagementTestClient.authorized(server, path).header(LocalApiSecurity.CATALOG_HEADER, id));
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void await(ManagementServer server, Predicate<CatalogStatus> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.test(server.catalog().status())) return;
            Thread.sleep(20);
        }
        fail("Catalog state did not arrive: " + server.catalog().status());
    }
}
