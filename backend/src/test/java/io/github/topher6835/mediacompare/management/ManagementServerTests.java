package io.github.topher6835.mediacompare.management;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagementServerTests {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void statusAndFrontendWorkWithoutACatalogAndApiNeverFallsBackToHtml() throws Exception {
        Path build = frontendBuild();
        try (ManagementServer server = ManagementServer.start(build, 0)) {
            assertEquals("127.0.0.1", server.baseUri().getHost());
            var status = get(server, "/api/management/status");
            assertEquals(200, status.statusCode());
            assertEquals("{\"service\":\"media-compare-management\",\"catalogState\":\"NOT_STARTED\"}", status.body().strip());
            assertEquals("no-store", status.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("<html>React entry</html>", get(server, "/").body());
            assertEquals("<html>React entry</html>", get(server, "/library/items/42").body());
            var script = get(server, "/assets/app.js");
            assertEquals("console.log('React build')", script.body());
            assertTrue(script.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
            for (String path : new String[] {"/api/sources", "/api/management/status/extra", "/assets/missing.js", "/missing.css"}) {
                var missing = get(server, path);
                assertEquals(404, missing.statusCode(), path);
                assertFalse(missing.body().contains("React entry"), path);
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(java.util.List.of(build), files.toList());
        }
    }

    @Test
    void onlyReadMethodsAreAccepted() throws Exception {
        try (ManagementServer server = ManagementServer.start(frontendBuild(), 0)) {
            for (String path : new String[] {"/", "/api/management/status"}) {
                var post = client.send(HttpRequest.newBuilder(server.baseUri().resolve(path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(405, post.statusCode());
                assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
                var head = client.send(HttpRequest.newBuilder(server.baseUri().resolve(path))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, head.statusCode());
                assertTrue(head.body().isEmpty());
            }
        }
    }

    @Test
    void refusesTraversalAndDoesNotListDirectories() throws Exception {
        Path build = frontendBuild();
        Files.writeString(directory.resolve("private.txt"), "private");
        try (ManagementServer server = ManagementServer.start(build, 0)) {
            assertEquals(403, get(server, "/%2e%2e/private.txt").statusCode());
            assertEquals(400, get(server, "/%5c..%5cprivate.txt").statusCode());
            assertEquals(404, get(server, "/assets/").statusCode());
        }
    }

    @Test
    void refusesLinksOutsideTheFrontendBuild() throws Exception {
        Path build = frontendBuild();
        Path outside = Files.writeString(directory.resolve("private.txt"), "private");
        try {
            Files.createSymbolicLink(build.resolve("leak.txt"), outside);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.assumeTrue(false, "Host cannot create test symlinks");
        }
        try (ManagementServer server = ManagementServer.start(build, 0)) {
            assertEquals(403, get(server, "/leak.txt").statusCode());
        }
    }

    @Test
    void missingBuildIsRejectedBeforeServing() throws Exception {
        assertThrows(java.io.IOException.class, () -> ManagementServer.start(directory, 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void entryPointPreservesSessionIndependentStartupAndHonorsBrowserFlag(boolean noBrowser) throws Exception {
        Path build = frontendBuild();
        Path classes = Path.of(ManagementApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        // Headless mode exercises the unsupported-browser fallback without opening a real browser.
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=true", "-cp", classes.toString(),
                ManagementApplication.class.getName(), build.toString(), "0"));
        if (noBrowser) command.add("--no-browser");
        String browserFallback = "Open the address above in your browser.";
        Path output = directory.resolve("management.log");
        var builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("MEDIA_COMPARE_SESSION_ROOT");
        Process process = builder.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String log = "";
            while (System.nanoTime() < deadline) {
                log = Files.readString(output);
                if (log.contains("\n") && (noBrowser || log.contains(browserFallback)) || !process.isAlive()) break;
                Thread.sleep(25);
            }
            String line = log.lines().findFirst().orElse("");
            assertTrue(line.startsWith("Media Compare management: "), line);
            URI base = URI.create(line.substring("Media Compare management: ".length()));
            var response = client.send(HttpRequest.newBuilder(base.resolve("/api/management/status"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("NOT_STARTED"));
            assertFalse(Files.exists(directory.resolve("catalog.db")));
            assertFalse(Files.exists(directory.resolve("data")));
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(!noBrowser, Files.readString(output).contains(browserFallback),
                "Default launch should attempt browser opening; --no-browser should suppress it");
    }

    private Path frontendBuild() throws Exception {
        Path build = Files.createDirectory(directory.resolve("frontend build"));
        Files.writeString(build.resolve("index.html"), "<html>React entry</html>");
        Files.createDirectory(build.resolve("assets"));
        Files.writeString(build.resolve("assets/app.js"), "console.log('React build')");
        return build;
    }

    private HttpResponse<String> get(ManagementServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
