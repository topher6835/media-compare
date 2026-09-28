package io.github.topher6835.mediacompare.preview;

import java.nio.charset.StandardCharsets;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact generator/configuration provenance; this milestone supplies no generator. */
public record PreviewDefinition(
        String generatorId,
        String generatorVersion,
        long configurationVersion,
        String configurationHash,
        String configurationJson) {

    public static final int MAX_JSON_UTF8_BYTES = 128 * 1_024;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public PreviewDefinition {
        requireText(generatorId, "generatorId");
        requireText(generatorVersion, "generatorVersion");
        requireText(configurationHash, "configurationHash");
        if (configurationVersion <= 0) {
            throw new IllegalArgumentException("configurationVersion must be positive");
        }
        requireText(configurationJson, "configurationJson");
        if (configurationJson.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_UTF8_BYTES) {
            throw new IllegalArgumentException("configurationJson exceeds 128 KiB of UTF-8");
        }
        try {
            JsonNode root = JSON_MAPPER.readTree(configurationJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("configurationJson must be a JSON object");
            }
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("configurationJson must be a valid JSON object", exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(field + " must be nonblank valid Unicode");
        }
    }
}
