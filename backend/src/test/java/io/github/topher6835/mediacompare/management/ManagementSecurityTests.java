package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementSecurityTests {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void capabilityIsRequiredForStatusBrowsingStartingAndForwarding() throws Exception {
        try (var server = ManagementServer.start(frontend(), 0)) {
            for (String path : List.of("status", "filesystem/roots", "filesystem/directories?path=/", "filesystem/validate?path=/", "open")) {
                var request = HttpRequest.newBuilder(server.baseUri().resolve("/api/management/" + path));
                if (path.equals("open")) request.POST(HttpRequest.BodyPublishers.ofString(directory.toString()));
                assertEquals(403, send(request).statusCode(), path);
                assertEquals(403, send(request.header(LocalApiSecurity.ACCESS_HEADER, "wrong")).statusCode(), path);
            }
            assertEquals(403, send(HttpRequest.newBuilder(server.baseUri().resolve("/api/health"))).statusCode());
            assertEquals(200, send(ManagementTestClient.authorized(server, "/api/management/status")).statusCode());
            assertEquals(405, send(ManagementTestClient.authorized(server, "/api/management/open")).statusCode());
            assertEquals(403, send(HttpRequest.newBuilder(server.baseUri().resolve("/api/management/bootstrap"))).statusCode());
            assertFalse(server.catalog().status().processAlive());
        }
    }

    @Test void maliciousOriginsNullOriginAndCrossSiteRequestsAreDeniedEvenWithCapability() throws Exception {
        try (var server = ManagementServer.start(frontend(), 0)) {
            for (String origin : List.of("https://evil.example", "null", "http://localhost:5173", "http://127.0.0.1:9999")) {
                for (String path : List.of("/api/management/bootstrap", "/api/management/filesystem/roots", "/api/management/open", "/")) {
                    var response = send(ManagementTestClient.authorized(server, path).header("Origin", origin)
                            .header("X-Media-Compare-Bootstrap", "1"));
                    assertEquals(403, response.statusCode(), origin + path);
                    assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
                }
            }
            for (String site : List.of("cross-site", "same-site")) {
                assertEquals(403, send(ManagementTestClient.authorized(server, "/api/management/status")
                        .header("Sec-Fetch-Site", site)).statusCode());
            }
            assertEquals(200, send(ManagementTestClient.authorized(server, "/api/management/status")
                    .header("Origin", server.baseUri().toString().replaceAll("/$", ""))).statusCode());
            var preflight = send(HttpRequest.newBuilder(server.baseUri().resolve("/api/management/open"))
                    .header("Origin", "https://evil.example").method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
            assertEquals(403, preflight.statusCode());
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    @Test void unexpectedHostCannotServeEvenTheFrontendOrBootstrap() throws Exception {
        try (var server = ManagementServer.start(frontend(), 0)) {
            for (String host : List.of("rebind.evil:8081", "localhost:" + server.baseUri().getPort(), "127.0.0.1:1")) {
                try (var socket = new Socket("127.0.0.1", server.baseUri().getPort())) {
                    socket.setSoTimeout(3000);
                    socket.getOutputStream().write(("GET /api/management/bootstrap HTTP/1.1\r\nHost: " + host
                            + "\r\nX-Media-Compare-Bootstrap: 1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(response.startsWith("HTTP/1.1 403"), response);
                    assertFalse(response.contains("capability"));
                }
            }
        }
    }

    @Test void explicitDevelopmentOriginSupportsTheViteProxyWithoutCors() throws Exception {
        try (var server = ManagementServer.start(frontend(), 0, "http://localhost:5173")) {
            var response = send(ManagementTestClient.authorized(server, "/api/management/open")
                    .header("Origin", "http://localhost:5173").header("Content-Type", "text/plain;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("relative")));
            assertEquals(400, response.statusCode());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
        for (String origin : List.of("https://evil.example", "http://192.168.1.2:5173", "http://localhost:5173/path")) {
            assertThrows(IllegalArgumentException.class, () -> new LocalApiSecurity(8081, origin));
        }
    }

    private Path frontend() throws Exception {
        Path build = Files.createDirectory(directory.resolve("frontend"));
        Files.writeString(build.resolve("index.html"), "<html>React</html>");
        return build;
    }
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
