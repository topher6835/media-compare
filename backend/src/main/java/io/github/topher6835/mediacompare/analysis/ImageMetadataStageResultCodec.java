package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;

import io.github.topher6835.mediacompare.scan.InvalidStageResultException;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ImageMetadataStageResultCodec {

    private final JsonMapper jsonMapper;

    public ImageMetadataStageResultCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(ImageMetadataStageResult result) {
        Objects.requireNonNull(result, "result");
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new InvalidStageResultException("Could not serialize image metadata stage result", exception);
        }
    }

    public ImageMetadataStageResult read(String resultJson) {
        try {
            String json = Objects.requireNonNull(resultJson, "resultJson");
            JsonNode stored = jsonMapper.readTree(json);
            requireExactSchema(stored);
            return jsonMapper.readValue(json, ImageMetadataStageResult.class);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStageResultException("Invalid image metadata stage result", exception);
        }
    }

    private static void requireExactSchema(JsonNode stored) {
        if (!stored.isObject() || stored.size() != 6
                || !isIntegral(stored, "version")
                || !isIntegral(stored, "candidatesAttempted")
                || !isIntegral(stored, "completedAvailable")
                || !isIntegral(stored, "completedUnsupported")
                || !isIntegral(stored, "failed")
                || !isIntegral(stored, "staleOrUnavailable")) {
            throw new IllegalArgumentException("Stored result does not match the versioned schema");
        }
    }

    private static boolean isIntegral(JsonNode stored, String fieldName) {
        JsonNode value = stored.get(fieldName);
        return value != null && value.isIntegralNumber();
    }
}
