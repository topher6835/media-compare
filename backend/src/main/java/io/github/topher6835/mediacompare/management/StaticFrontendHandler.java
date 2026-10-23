package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** Serves one explicit frontend build, with SPA fallback and no directory listing. */
final class StaticFrontendHandler implements HttpHandler {
    private final Path root;

    StaticFrontendHandler(Path frontendRoot) throws IOException {
        root = frontendRoot.toRealPath();
        Path index = root.resolve("index.html");
        if (!Files.isDirectory(root) || !Files.isRegularFile(index) || !index.toRealPath().startsWith(root)) {
            throw new IOException("Frontend build must contain index.html; run npm run build first");
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!requireReadMethod(exchange)) return;
            String path = exchange.getRequestURI().getPath();
            if (path.indexOf('\\') >= 0 || !path.startsWith("/") || path.startsWith("//")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Path file;
            try {
                file = root.resolve(path.substring(1)).normalize();
            } catch (InvalidPathException exception) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (!file.startsWith(root)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!Files.exists(file)) {
                // Missing assets must stay 404s rather than return HTML as JavaScript.
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (path.startsWith("/assets/") || last.contains(".")) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                file = root.resolve("index.html");
            } else if (file.equals(root)) {
                file = root.resolve("index.html");
            }
            if (!file.toRealPath().startsWith(root)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType(file));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, Files.size(file));
                Files.copy(file, exchange.getResponseBody());
            }
        }
    }

    static boolean requireReadMethod(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod()) || "HEAD".equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        exchange.sendResponseHeaders(405, -1);
        return false;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "ico" -> "image/x-icon";
            case "webp" -> "image/webp";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }
}
