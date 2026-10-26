package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Session-independent HTTP service owning one catalog supervisor, outside the child's Spring context. */
public final class ManagementServer implements AutoCloseable {
    private final HttpServer server;
    private final CatalogProcessSupervisor catalog;

    private ManagementServer(HttpServer server, CatalogProcessSupervisor catalog) {
        this.server = server;
        this.catalog = catalog;
    }

    public static ManagementServer start(Path frontendRoot, int port) throws IOException {
        return start(frontendRoot, port, new CatalogProcessSupervisor());
    }

    static ManagementServer start(Path frontendRoot, int port, CatalogProcessSupervisor catalog) throws IOException {
        // Validate the build before binding. No Session or application-data directory is touched.
        StaticFrontendHandler frontend = new StaticFrontendHandler(frontendRoot);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ManagementServer management = new ManagementServer(server, catalog);
        server.createContext("/api", management::handleApi);
        server.createContext("/", frontend);
        server.start();
        return management;
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** Internal Java control only; no HTTP start/stop endpoint is registered. */
    public CatalogProcessSupervisor catalog() { return catalog; }

    private void handleApi(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!StaticFrontendHandler.requireReadMethod(exchange)) return;
            if (!"/api/management/status".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                CatalogStatus status = catalog.status();
                String failure = status.failure() == null ? "null" : "\"" + status.failure().name() + "\"";
                byte[] body = ("{\"service\":\"media-compare-management\",\"catalogState\":\"" + status.state().name()
                        + "\",\"processAlive\":" + status.processAlive() + ",\"failureCode\":" + failure
                        + ",\"exitCode\":" + status.exitCode() + "}\n").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }
    }

    @Override
    public void close() {
        // If the child remains alive, close throws and explicit callers keep HTTP/status available.
        catalog.close();
        server.stop(1);
    }
}
