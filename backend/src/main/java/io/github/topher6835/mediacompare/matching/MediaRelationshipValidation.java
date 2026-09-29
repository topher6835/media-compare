package io.github.topher6835.mediacompare.matching;

import java.nio.charset.StandardCharsets;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared validation for relationship artifacts and their exact producing definitions. */
final class MediaRelationshipValidation {
    static final int MAX_JSON_UTF8_BYTES = 128 * 1_024;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private MediaRelationshipValidation() {
    }

    static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    static void requireConfigurationVersion(long configurationVersion) {
        if (configurationVersion <= 0) {
            throw new IllegalArgumentException("configurationVersion must be positive");
        }
    }

    static void requireJsonObject(String json, String fieldName) {
        requireNonBlank(json, fieldName);
        if (json.length() > MAX_JSON_UTF8_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_UTF8_BYTES) {
            throw new IllegalArgumentException(fieldName + " exceeds 128 KiB of UTF-8");
        }
        // JDBC must be able to preserve the supplied JSON as UTF-8 without replacement characters.
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= json.length() || !Character.isLowSurrogate(json.charAt(index + 1))) {
                    throw new IllegalArgumentException(fieldName + " contains malformed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(fieldName + " contains malformed Unicode");
            }
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(fieldName + " must be a JSON object");
            }
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid JSON object", exception);
        }
    }
}
