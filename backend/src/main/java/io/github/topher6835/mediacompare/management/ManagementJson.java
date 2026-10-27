package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;

/** Small JDK-only response encoder; management startup does not need a Spring/Jackson runtime. */
final class ManagementJson {
    private ManagementJson() { }

    static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = encode(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(status, -1);
        else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .map(entry -> encode(entry.getKey().toString()) + ":" + encode(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> list) {
            var items = new java.util.ArrayList<String>();
            list.forEach(item -> items.add(encode(item)));
            return "[" + String.join(",", items) + "]";
        }
        var text = new StringBuilder("\"");
        for (char character : value.toString().toCharArray()) {
            switch (character) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                default -> {
                    if (character < 32 || Character.isSurrogate(character)) text.append(String.format("\\u%04x", (int) character));
                    else text.append(character);
                }
            }
        }
        return text.append('"').toString();
    }
}
