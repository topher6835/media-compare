package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;

/** Protects the local browser boundary; local programs running as the user are trusted. */
final class LocalApiSecurity {
    static final String ACCESS_HEADER = "X-Media-Compare-Access";
    static final String CATALOG_HEADER = "X-Media-Compare-Catalog";
    private final String capability = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new SecureRandom().generateSeed(32));
    private final String authority;
    private final String origin;
    private final String developmentOrigin;

    LocalApiSecurity(int port, String developmentOrigin) {
        authority = "127.0.0.1:" + port;
        origin = "http://" + authority;
        if (developmentOrigin != null) {
            URI uri = URI.create(developmentOrigin);
            if (!"http".equals(uri.getScheme()) || !List.of("127.0.0.1", "localhost").contains(uri.getHost())
                    || uri.getPort() < 1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("Development origin must be an explicit HTTP localhost or 127.0.0.1 origin with port");
            }
        }
        this.developmentOrigin = developmentOrigin;
    }

    boolean allowBrowser(HttpExchange exchange) throws IOException {
        var headers = exchange.getRequestHeaders();
        var hosts = headers.get("Host");
        if (hosts == null || hosts.size() != 1 || !authority.equals(hosts.getFirst())) return deny(exchange);
        var origins = headers.get("Origin");
        String requestOrigin = headers.getFirst("Origin");
        boolean dev = developmentOrigin != null && developmentOrigin.equals(requestOrigin);
        if (origins != null && (origins.size() != 1 || !(origin.equals(requestOrigin) || dev))) return deny(exchange);
        String site = headers.getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none") && !dev) return deny(exchange);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        return true;
    }

    void bootstrap(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) { deny(exchange); return; }
        // Custom header forces a preflight for cross-origin scripts. OPTIONS never receives CORS permission.
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Media-Compare-Bootstrap"))) { deny(exchange); return; }
        exchange.getResponseHeaders().add("Set-Cookie", "mc_access=" + capability + "; Path=/api/previews; HttpOnly; SameSite=Strict");
        ManagementJson.send(exchange, 200, java.util.Map.of("service", "media-compare-management", "capability", capability));
    }

    boolean authorize(HttpExchange exchange) throws IOException {
        String supplied = exchange.getRequestHeaders().getFirst(ACCESS_HEADER);
        // img elements cannot send custom headers. Only generation-scoped, read-only previews use the strict cookie.
        if (supplied == null && exchange.getRequestURI().getPath().startsWith("/api/previews/")
                && List.of("GET", "HEAD").contains(exchange.getRequestMethod())) {
            for (String cookie : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
                for (String part : cookie.split(";")) {
                    if (part.strip().startsWith("mc_access=")) supplied = part.strip().substring("mc_access=".length());
                }
            }
        }
        if (supplied != null && MessageDigest.isEqual(capability.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) return true;
        return deny(exchange);
    }

    private boolean deny(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(403, -1);
        return false;
    }
}
