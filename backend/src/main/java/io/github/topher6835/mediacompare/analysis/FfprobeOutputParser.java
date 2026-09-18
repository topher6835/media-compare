package io.github.topher6835.mediacompare.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class FfprobeOutputParser {

    private static final int MAX_IDENTIFIER_LENGTH = 100;
    private static final int MAX_FORMAT_NAME_LENGTH = 1_000;
    private static final int MAX_DURATION_LENGTH = 100;
    private static final int MAX_BRANDS_LENGTH = 1_000;

    private final JsonMapper jsonMapper;

    FfprobeOutputParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    FfprobeOutput parse(String probeJson) {
        if (probeJson == null) {
            throw invalid("Probe output is required");
        }
        try {
            JsonNode root = jsonMapper.readTree(probeJson);
            requireObject(root, "Probe output");
            FfprobeFormat format = parseFormat(requiredField(root, "format"));
            JsonNode streamsNode = requiredField(root, "streams");
            if (!streamsNode.isArray()) {
                throw invalid("streams must be an array");
            }

            var streams = new ArrayList<FfprobeStream>();
            var indexes = new HashSet<Integer>();
            for (JsonNode streamNode : streamsNode) {
                FfprobeStream stream = parseStream(streamNode);
                if (!indexes.add(stream.index())) {
                    throw invalid("Stream indexes must be unique");
                }
                streams.add(stream);
            }
            return new FfprobeOutput(format, List.copyOf(streams));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalid("Probe output is malformed", exception);
        }
    }

    private static FfprobeFormat parseFormat(JsonNode formatNode) {
        requireObject(formatNode, "format");
        String formatName = requiredString(formatNode, "format_name", MAX_FORMAT_NAME_LENGTH);
        String duration = optionalString(formatNode, "duration", MAX_DURATION_LENGTH);
        JsonNode tags = formatNode.get("tags");
        if (tags != null && !tags.isNull() && !tags.isObject()) {
            throw invalid("format tags must be an object or null");
        }
        String majorBrand = tags == null || tags.isNull()
                ? null : optionalString(tags, "major_brand", MAX_BRANDS_LENGTH);
        String compatibleBrands = tags == null || tags.isNull()
                ? null : optionalString(tags, "compatible_brands", MAX_BRANDS_LENGTH);
        return new FfprobeFormat(formatName, duration, majorBrand, compatibleBrands);
    }

    private static FfprobeStream parseStream(JsonNode streamNode) {
        requireObject(streamNode, "stream");
        int index = requiredNonnegativeInt(streamNode, "index");
        String codecType = normalizedIdentifier(
                requiredString(streamNode, "codec_type", MAX_IDENTIFIER_LENGTH), "Codec type");
        String codecName = optionalString(streamNode, "codec_name", MAX_IDENTIFIER_LENGTH);
        if (codecName != null) {
            codecName = normalizedIdentifier(codecName, "Codec name");
        }
        return new FfprobeStream(
                index,
                codecType,
                codecName,
                optionalNonnegativeInt(streamNode, "width"),
                optionalNonnegativeInt(streamNode, "height"),
                parseDisposition(requiredField(streamNode, "disposition")));
    }

    private static FfprobeDisposition parseDisposition(JsonNode dispositionNode) {
        requireObject(dispositionNode, "stream disposition");
        return new FfprobeDisposition(
                requiredFlag(dispositionNode, "default"),
                requiredFlag(dispositionNode, "attached_pic"),
                requiredFlag(dispositionNode, "timed_thumbnails"),
                requiredFlag(dispositionNode, "still_image"));
    }

    private static String normalizedIdentifier(String value, String description) {
        return ImageMediaMetadata.requireNormalizedName(
                value.trim().toLowerCase(Locale.ROOT), description);
    }

    private static JsonNode requiredField(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            throw invalid("Missing required field: " + fieldName);
        }
        return value;
    }

    private static String requiredString(JsonNode object, String fieldName, int maximumLength) {
        String value = optionalString(object, fieldName, maximumLength);
        if (value == null || value.isEmpty()) {
            throw invalid(fieldName + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalString(JsonNode object, String fieldName, int maximumLength) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > maximumLength) {
            throw invalid(fieldName + " must be a bounded string or null");
        }
        return value.textValue();
    }

    private static int requiredNonnegativeInt(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid(fieldName + " must be a nonnegative integer within range");
        }
        return value.intValue();
    }

    private static Integer optionalNonnegativeInt(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid(fieldName + " must be a nonnegative integer within range or null");
        }
        return value.intValue();
    }

    private static boolean requiredFlag(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || (value.intValue() != 0 && value.intValue() != 1)) {
            throw invalid(fieldName + " must be 0 or 1");
        }
        return value.intValue() == 1;
    }

    private static void requireObject(JsonNode value, String description) {
        if (value == null || !value.isObject()) {
            throw invalid(description + " must be an object");
        }
    }

    private static InvalidOutputException invalid(String message) {
        return new InvalidOutputException(message);
    }

    private static InvalidOutputException invalid(String message, Throwable cause) {
        if (cause instanceof InvalidOutputException invalid) {
            return invalid;
        }
        return new InvalidOutputException(message, cause);
    }

    static final class InvalidOutputException extends RuntimeException {
        private InvalidOutputException(String message) {
            super(message);
        }

        private InvalidOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
