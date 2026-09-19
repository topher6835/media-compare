package io.github.topher6835.mediacompare.location;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict deterministic codec for the version-1 structured location path document. */
public final class LocationPathCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = 64 * 1_024;

    private final JsonMapper jsonMapper;

    public LocationPathCodec() {
        this(JsonMapper.builder().build());
    }

    LocationPathCodec(JsonMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper").rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public String encode(LocationPath path) {
        Objects.requireNonNull(path, "path");
        try {
            String json = jsonMapper.writeValueAsString(List.of(
                    "lp1", path.dialect().persistedName(), path.rootFields(), path.components()));
            requireBounded(json);
            return json;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Could not encode location path", exception);
        }
    }

    public LocationPath decode(String json) {
        try {
            LocationPathValidation.requireWellFormedUnicode(json, "Location path JSON");
            requireBounded(json);
            JsonNode root = jsonMapper.readTree(json);
            if (root == null || !root.isArray() || root.size() != 4) {
                throw new IllegalArgumentException("Location path JSON must be a four-element array");
            }
            String version = text(root.get(0), "Location path version");
            if (!"lp1".equals(version)) {
                throw new IllegalArgumentException("Unsupported location path version");
            }
            LocationDialect dialect = LocationDialect.fromPersistedName(
                    text(root.get(1), "Location path dialect"));
            return new LocationPath(dialect,
                    strings(root.get(2), "Location path root fields"),
                    strings(root.get(3), "Location path components"));
        } catch (JacksonException | NullPointerException exception) {
            throw new IllegalArgumentException("Invalid location path JSON", exception);
        }
    }

    private static List<String> strings(JsonNode node, String description) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(description + " must be an array");
        }
        var values = new ArrayList<String>(node.size());
        for (JsonNode value : node) {
            values.add(text(value, description + " entry"));
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String description) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(description + " must be a string");
        }
        String value = node.asText();
        LocationPathValidation.requireWellFormedUnicode(value, description);
        return value;
    }

    private static void requireBounded(String json) {
        Objects.requireNonNull(json, "Location path JSON is required");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_UTF8_BYTES) {
            throw new IllegalArgumentException("Location path JSON exceeds 64 KiB");
        }
    }
}
