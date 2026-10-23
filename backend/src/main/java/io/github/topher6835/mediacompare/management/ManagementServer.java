package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Owns only management HTTP resources; future catalog supervision belongs outside Spring. */
public final class ManagementServer implements AutoCloseable {
    private static final byte[] STATUS = """
            {"service":"media-compare-management","catalogState":"NOT_STARTED"}
            """.getBytes(StandardCharsets.UTF_8);
    private final HttpServer server;

    private ManagementServer(HttpServer server) {
        this.server = server;
    }

    public static ManagementServer start(Path frontendRoot, int port) throws IOException {
        // Validate the build before binding. No Session or application-data directory is touched.
        StaticFrontendHandler frontend = new StaticFrontendHandler(frontendRoot);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", ManagementServer::handleApi);
        server.createContext("/", frontend);
        server.start();
        return new ManagementServer(server);
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
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
                exchange.sendResponseHeaders(200, STATUS.length);
                exchange.getResponseBody().write(STATUS);
            }
        }
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
