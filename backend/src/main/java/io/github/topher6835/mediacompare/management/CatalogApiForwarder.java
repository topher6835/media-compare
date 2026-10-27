package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/** Fixed-loopback forwarding to a single immutable launch snapshot, never an arbitrary URL. */
final class CatalogApiForwarder implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final CatalogProcessSupervisor catalog;

    CatalogApiForwarder(CatalogProcessSupervisor catalog) { this.catalog = catalog; }

    void forward(HttpExchange exchange, String expectedId) throws IOException {
        if (expectedId == null || !expectedId.equals(catalog.catalogId())) {
            ManagementJson.send(exchange, 409, Map.of("message", "This request belongs to a different Session. Reload before continuing."));
            return;
        }
        var route = catalog.route(expectedId);
        if (route == null) {
            ManagementJson.send(exchange, 503, Map.of("message", "The selected catalog is unavailable."));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith("/api/") || path.contains("\\") || List.of(path.split("/", -1)).stream()
                .anyMatch(part -> part.equals(".") || part.equals(".."))) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        if (!List.of("GET", "HEAD", "POST").contains(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(1_048_577);
        if (body.length > 1_048_576) { exchange.sendResponseHeaders(413, -1); return; }
        URI target = URI.create("http://127.0.0.1:" + route.port() + exchange.getRequestURI().toASCIIString());
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(60))
                .header(ManagedCatalogSecurity.HEADER, route.credential())
                .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : List.of("Accept", "Content-Type", "If-None-Match", "X-Media-Compare-Reveal")) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) request.header(name, value);
        }
        HttpResponse<java.io.InputStream> response;
        try { response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ManagementJson.send(exchange, 503, Map.of("message", "The catalog request was interrupted."));
            return;
        } catch (IOException unavailable) {
            ManagementJson.send(exchange, 503, Map.of("message", "The catalog could not be reached."));
            return;
        }
        try (var input = response.body()) {
            if (catalog.route(expectedId) == null) {
                ManagementJson.send(exchange, 409, Map.of("message", "The selected catalog is no longer active."));
                return;
            }
            // Do not forward cookies, CORS, redirects, server diagnostics or upstream cache permissions.
            for (String name : List.of("Content-Type", "ETag")) {
                response.headers().firstValue(name).ifPresent(value -> exchange.getResponseHeaders().set(name, value));
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            boolean noBody = exchange.getRequestMethod().equals("HEAD") || response.statusCode() == 204 || response.statusCode() == 304;
            exchange.sendResponseHeaders(response.statusCode(), noBody ? -1 : 0);
            if (!noBody) input.transferTo(exchange.getResponseBody());
        }
    }

    @Override public void close() { client.close(); }
}
