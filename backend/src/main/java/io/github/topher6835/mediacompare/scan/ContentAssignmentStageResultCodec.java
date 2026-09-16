package io.github.topher6835.mediacompare.scan;

import java.util.Objects;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ContentAssignmentStageResultCodec {

    private final JsonMapper jsonMapper;

    public ContentAssignmentStageResultCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(ContentAssignmentStageResult result) {
        Objects.requireNonNull(result, "result");
        try {
            return jsonMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            throw new InvalidStageResultException("Could not serialize assignment stage result", exception);
        }
    }

    public ContentAssignmentStageResult read(String resultJson) {
        try {
            String json = Objects.requireNonNull(resultJson, "resultJson");
            var stored = jsonMapper.readTree(json);
            requireExactSchema(stored);
            ContentAssignmentStageResult result = jsonMapper.readValue(
                    json, ContentAssignmentStageResult.class);
            return result;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidStageResultException("Invalid assignment stage result", exception);
        }
    }

    private static void requireExactSchema(JsonNode stored) {
        if (!stored.isObject() || stored.size() != 3
                || !isIntegral(stored, "version")
                || !isIntegral(stored, "assignedCount")
                || !isIntegral(stored, "skippedCount")) {
            throw new IllegalArgumentException("Stored result does not match the versioned schema");
        }
    }

    private static boolean isIntegral(JsonNode stored, String fieldName) {
        JsonNode value = stored.get(fieldName);
        return value != null && value.isIntegralNumber();
    }
}
