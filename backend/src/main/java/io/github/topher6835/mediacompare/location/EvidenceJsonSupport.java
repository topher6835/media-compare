package io.github.topher6835.mediacompare.location;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class EvidenceJsonSupport {
    static final int MAX_DOCUMENT_UTF8_BYTES = 128 * 1_024;

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final LocationPathCodec locationPathCodec = new LocationPathCodec();

    JsonNode parse(String json) {
        try {
            requireBounded(json);
            EvidenceValues.requireWellFormedUnicode(json, "Evidence JSON");
            JsonNode root = jsonMapper.readTree(json);
            requireObject(root, "Evidence JSON");
            return root;
        } catch (JacksonException | NullPointerException exception) {
            throw new IllegalArgumentException("Invalid evidence JSON", exception);
        }
    }

    String write(Map<String, Object> fields) {
        try {
            String json = jsonMapper.writeValueAsString(fields);
            requireBounded(json);
            return json;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Could not encode evidence JSON", exception);
        }
    }

    JsonNode encodedPath(LocationPath path) {
        try {
            return jsonMapper.readTree(locationPathCodec.encode(path));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Could not encode structured location path", exception);
        }
    }

    LocationPath requiredPath(JsonNode object, String fieldName) {
        return locationPathCodec.decode(requiredField(object, fieldName).toString());
    }

    static JsonNode requiredField(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        return value;
    }

    static String requiredString(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be a non-empty string");
        }
        String result = value.textValue();
        EvidenceValues.requireWellFormedUnicode(result, fieldName);
        return result;
    }

    static String optionalString(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be a non-empty string when present");
        }
        String result = value.textValue();
        EvidenceValues.requireWellFormedUnicode(result, fieldName);
        return result;
    }

    static int requiredInt(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(fieldName + " must be an integer within range");
        }
        return value.intValue();
    }

    static long requiredLong(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(fieldName + " must be a long integer within range");
        }
        return value.longValue();
    }

    static boolean requiredBoolean(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(fieldName + " must be a boolean");
        }
        return value.booleanValue();
    }

    static void requireObject(JsonNode value, String description) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(description + " must be an object");
        }
    }

    static void requireExactFields(JsonNode object, Set<String> expected) {
        var actual = new HashSet<String>();
        object.properties().forEach(property -> actual.add(property.getKey()));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Evidence JSON does not match the required schema");
        }
    }

    static void requireOnlyFields(JsonNode object, Set<String> permitted) {
        object.properties().forEach(property -> {
            if (!permitted.contains(property.getKey())) {
                throw new IllegalArgumentException("Evidence JSON contains an unknown field");
            }
        });
    }

    static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    private static void requireBounded(String json) {
        Objects.requireNonNull(json, "Evidence JSON is required");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_UTF8_BYTES) {
            throw new IllegalArgumentException("Evidence JSON exceeds 128 KiB");
        }
    }
}
