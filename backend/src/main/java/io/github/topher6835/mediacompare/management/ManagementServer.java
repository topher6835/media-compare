package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Session-independent HTTP service owning one catalog supervisor, outside the child's Spring context. */
public final class ManagementServer implements AutoCloseable {
    private final HttpServer server;
    private final CatalogProcessSupervisor catalog;
    private final LocalApiSecurity security;
    private final FilesystemBrowser filesystem = new FilesystemBrowser();
    private final CatalogApiForwarder forwarder;
    private final SessionLifecycleAdmission admission;
    private final SessionCreationOperations creations;
    private final SessionCreationApi creationApi;
    private final ExecutorService requests = Executors.newFixedThreadPool(8);

    private ManagementServer(HttpServer server, CatalogProcessSupervisor catalog, String developmentOrigin,
            SessionCreationOperations.Creator creator) {
        this.server = server;
        this.catalog = catalog;
        security = new LocalApiSecurity(server.getAddress().getPort(), developmentOrigin);
        forwarder = new CatalogApiForwarder(catalog);
        admission = new SessionLifecycleAdmission(catalog);
        creations = creator == null ? new SessionCreationOperations(catalog, admission)
                : new SessionCreationOperations(catalog, admission, creator, System::nanoTime, Duration.ofMinutes(10));
        creationApi = new SessionCreationApi(creations);
    }

    public static ManagementServer start(Path frontendRoot, int port) throws IOException {
        return start(frontendRoot, port, (String) null);
    }

    public static ManagementServer start(Path frontendRoot, int port, String developmentOrigin) throws IOException {
        return start(frontendRoot, port, new CatalogProcessSupervisor(), developmentOrigin);
    }

    static ManagementServer start(Path frontendRoot, int port, CatalogProcessSupervisor catalog) throws IOException {
        return start(frontendRoot, port, catalog, null);
    }

    private static ManagementServer start(Path frontendRoot, int port, CatalogProcessSupervisor catalog, String developmentOrigin) throws IOException {
        return start(frontendRoot, port, catalog, developmentOrigin, null);
    }

    // Package-local creator seam supports deterministic IO admission tests without changing filesystem policy.
    static ManagementServer start(Path frontendRoot, int port, CatalogProcessSupervisor catalog,
            String developmentOrigin, SessionCreationOperations.Creator creator) throws IOException {
        StaticFrontendHandler frontend = new StaticFrontendHandler(frontendRoot);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ManagementServer management;
        try { management = new ManagementServer(server, catalog, developmentOrigin, creator); }
        catch (RuntimeException invalid) { server.stop(0); throw invalid; }
        server.setExecutor(management.requests);
        server.createContext("/api", management::handleApi);
        server.createContext("/", exchange -> {
            if (management.security.allowBrowser(exchange)) frontend.handle(exchange);
            else exchange.close();
        });
        server.start();
        return management;
    }

    public URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
    public CatalogProcessSupervisor catalog() { return catalog; }

    private void handleApi(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!security.allowBrowser(exchange)) return;
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/api/management/bootstrap")) { security.bootstrap(exchange); return; }
                if (!security.authorize(exchange)) return;
                if (path.startsWith("/api/management/")) handleManagement(exchange, path);
                else {
                    String id = exchange.getRequestHeaders().getFirst(LocalApiSecurity.CATALOG_HEADER);
                    if (id == null && path.startsWith("/api/previews/")) id = parameters(exchange).get("catalogId");
                    forwarder.forward(exchange, id);
                }
            } catch (IllegalArgumentException invalid) {
                ManagementJson.send(exchange, 400, Map.of("message", "Invalid API request."));
            }
        }
    }

    private void handleManagement(HttpExchange exchange, String path) throws IOException {
        try {
            if (path.equals(SessionCreationApi.PREFIX) || path.startsWith(SessionCreationApi.PREFIX + "/")) {
                creationApi.handle(exchange, path);
                return;
            }
            if (path.equals("/api/management/open")) {
                if (!requireMethod(exchange, "POST")) return;
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType == null || !contentType.equalsIgnoreCase("text/plain;charset=UTF-8")) {
                    exchange.sendResponseHeaders(415, -1); return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
                if (bytes.length > 65_536) { exchange.sendResponseHeaders(413, -1); return; }
                Path selected = filesystem.absolutePath(new String(bytes, StandardCharsets.UTF_8));
                try (var lease = admission.acquire()) {
                    var validation = filesystem.validate(selected);
                    if (!validation.get("kind").equals("VALID_SESSION")) { ManagementJson.send(exchange, 422, validation); return; }
                    lease.start(selected);
                }
                catch (IllegalStateException conflict) {
                    ManagementJson.send(exchange, 409, Map.of("message", "The previous catalog must fully exit before opening another Session.")); return;
                }
                ManagementJson.send(exchange, 202, status());
                return;
            }
            if (!StaticFrontendHandler.requireReadMethod(exchange)) return;
            switch (path) {
                case "/api/management/status" -> ManagementJson.send(exchange, 200, status());
                case "/api/management/filesystem/roots" -> ManagementJson.send(exchange, 200, filesystem.roots());
                case "/api/management/filesystem/directories" -> {
                    var query = parameters(exchange);
                    ManagementJson.send(exchange, 200, filesystem.list(filesystem.absolutePath(query.get("path")), query.getOrDefault("filter", "")));
                }
                case "/api/management/filesystem/validate" -> ManagementJson.send(exchange, 200,
                        filesystem.validate(filesystem.absolutePath(parameters(exchange).get("path"))));
                default -> exchange.sendResponseHeaders(404, -1);
            }
        } catch (SessionCreationOperations.Failure conflict) {
            ManagementJson.send(exchange, conflict.status, Map.of("code", conflict.code, "message", conflict.getMessage()));
        } catch (FilesystemBrowser.InvalidFilterException invalid) {
            ManagementJson.send(exchange, 400, Map.of("message", invalid.getMessage()));
        } catch (IllegalArgumentException invalid) {
            ManagementJson.send(exchange, 400, Map.of("message", "Enter a valid absolute directory path."));
        } catch (NoSuchFileException missing) {
            ManagementJson.send(exchange, 404, Map.of("message", "The directory does not exist."));
        } catch (IOException | SecurityException unavailable) {
            ManagementJson.send(exchange, 403, Map.of("message", "The directory is inaccessible or cannot be read."));
        }
    }

    private Map<String, Object> status() {
        synchronized (catalog) {
            CatalogStatus status = catalog.status();
            var response = new LinkedHashMap<String, Object>();
            response.put("service", "media-compare-management");
            response.put("catalogState", status.state().name());
            response.put("processAlive", status.processAlive());
            response.put("failureCode", status.failure() == null ? null : status.failure().name());
            response.put("exitCode", status.exitCode());
            response.put("catalogId", catalog.catalogId());
            return response;
        }
    }

    static Map<String, String> parameters(HttpExchange exchange) {
        var result = new LinkedHashMap<String, String>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return result;
        if (query.length() > 65_536) throw new IllegalArgumentException("Query too long");
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length < 2 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate parameter");
        }
        return result;
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equals(method)) return true;
        exchange.getResponseHeaders().set("Allow", method);
        exchange.sendResponseHeaders(405, -1);
        return false;
    }

    @Override public void close() {
        creations.shutdown(Duration.ofSeconds(30)); // Slow/uncertain creation keeps recovery/status available.
        catalog.close(); // A live child keeps management/status available after an explicit close failure.
        server.stop(1);
        requests.shutdownNow();
        forwarder.close();
    }
}
