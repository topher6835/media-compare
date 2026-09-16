package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;

import io.github.topher6835.mediacompare.scan.InvalidStageResultException;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ContentHashingStageResultCodec {

    private final JsonMapper jsonMapper;

    public ContentHashingStageResultCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(ContentHashingStageResult result) {
        Objects.requireNonNull(result, "result");
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new InvalidStageResultException("Could not serialize hashing stage result", exception);
        }
    }

    public ContentHashingStageResult read(String resultJson) {
        try {
            String json = Objects.requireNonNull(resultJson, "resultJson");
            var stored = jsonMapper.readTree(json);
            requireExactSchema(stored);
            ContentHashingStageResult result = jsonMapper.readValue(
                    json, ContentHashingStageResult.class);
            return result;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStageResultException("Invalid hashing stage result", exception);
        }
    }

    private static void requireExactSchema(JsonNode stored) {
        if (!stored.isObject() || stored.size() != 5
                || !isIntegral(stored, "version")
                || !isIntegral(stored, "hashedCount")
                || !isIntegral(stored, "cachedCount")
                || !isIntegral(stored, "skippedCount")
                || !isIntegral(stored, "failedCount")) {
            throw new IllegalArgumentException("Stored result does not match the versioned schema");
        }
    }

    private static boolean isIntegral(JsonNode stored, String fieldName) {
        JsonNode value = stored.get(fieldName);
        return value != null && value.isIntegralNumber();
    }
}
