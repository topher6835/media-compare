package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;

/** Authenticated by ManagementServer before dispatch. Only POST can reserve, submit or retry. */
final class SessionCreationApi {
    static final String PREFIX = "/api/management/session-creations";
    private final SessionCreationOperations operations;

    SessionCreationApi(SessionCreationOperations operations) { this.operations = operations; }

    void handle(HttpExchange exchange, String path) throws IOException {
        try {
            if (path.equals(PREFIX)) {
                if (isRead(exchange)) send(exchange, 200, operations.list());
                else if (post(exchange, "GET, HEAD, POST")) {
                    requireEmpty(exchange);
                    send(exchange, 201, operations.reserve());
                }
                return;
            }
            String[] parts = path.substring(PREFIX.length() + 1).split("/", -1);
            if (parts.length > 2 || parts[0].isEmpty() || parts[0].length() > 128) {
                exchange.sendResponseHeaders(404, -1); return;
            }
            String id = parts[0];
            if (parts.length == 1) {
                if (isRead(exchange)) send(exchange, 200, operations.status(id));
                else if (post(exchange, "GET, HEAD, POST")) {
                    var fields = form(exchange, Set.of("parentPath", "name"));
                    send(exchange, 202, operations.submit(id,
                            new SessionCreationOperations.Input(fields.get("parentPath"), fields.get("name"))));
                }
            } else if (parts[1].equals("retry-open")) {
                if (!post(exchange, "POST")) return;
                send(exchange, 201, operations.reserveRetry(id, form(exchange, Set.of("failedAttemptId")).get("failedAttemptId")));
            } else if (parts[1].equals("open")) {
                if (!post(exchange, "POST")) return;
                requireEmpty(exchange);
                send(exchange, 202, operations.submitRetry(id));
            } else exchange.sendResponseHeaders(404, -1);
        } catch (SessionCreationOperations.Failure failure) {
            ManagementJson.send(exchange, failure.status, Map.of("code", failure.code, "message", failure.getMessage()));
        } catch (IllegalArgumentException invalid) {
            ManagementJson.send(exchange, 400, Map.of("code", "INVALID_INPUT", "message", "Supply the expected UTF-8 form fields without duplicate fields."));
        }
    }

    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        if (ManagementJson.encode(value).getBytes(StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new SessionCreationOperations.Failure(500, "RESULT_UNAVAILABLE", "Inspect the retained operation and Session manually before retrying.");
        }
        ManagementJson.send(exchange, status, value);
    }

    private static Map<String, String> form(HttpExchange exchange, Set<String> expected) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.equalsIgnoreCase("application/x-www-form-urlencoded;charset=UTF-8")) {
            throw new SessionCreationOperations.Failure(415, "INVALID_CONTENT_TYPE", "Use application/x-www-form-urlencoded;charset=UTF-8.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
        if (bytes.length > 65_536) throw new SessionCreationOperations.Failure(413, "REQUEST_TOO_LARGE", "The creation request is too large.");
        for (byte value : bytes) if (value < 0) throw new IllegalArgumentException("Form must encode UTF-8 bytes");
        var fields = new LinkedHashMap<String, String>();
        for (String pair : new String(bytes, StandardCharsets.US_ASCII).split("&", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || fields.putIfAbsent(decode(parts[0]), decode(parts[1])) != null) {
                throw new IllegalArgumentException("Malformed or duplicate form field");
            }
        }
        if (!fields.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected form fields");
        return fields;
    }

    private static String decode(String text) {
        byte[] bytes = URLDecoder.decode(text, StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid UTF-8", invalid); }
    }

    private static void requireEmpty(HttpExchange exchange) throws IOException {
        if (exchange.getRequestBody().readNBytes(1).length != 0) throw new IllegalArgumentException("Expected an empty body");
    }

    private static boolean isRead(HttpExchange exchange) {
        return exchange.getRequestMethod().equals("GET") || exchange.getRequestMethod().equals("HEAD");
    }

    private static boolean post(HttpExchange exchange, String allowed) throws IOException {
        if (exchange.getRequestMethod().equals("POST")) return true;
        exchange.getResponseHeaders().set("Allow", allowed);
        exchange.sendResponseHeaders(405, -1);
        return false;
    }
}
