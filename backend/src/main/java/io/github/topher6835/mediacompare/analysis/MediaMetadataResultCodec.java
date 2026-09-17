package io.github.topher6835.mediacompare.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class MediaMetadataResultCodec {

    private final JsonMapper jsonMapper;

    public MediaMetadataResultCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(MediaMetadataResult result) {
        Objects.requireNonNull(result, "result");
        try {
            ObjectNode stored = jsonMapper.createObjectNode();
            stored.put("version", result.version());
            stored.put("outcome", result.outcome().name());
            if (result instanceof AvailableMediaMetadata available) {
                writeAvailable(stored, available);
            }
            return jsonMapper.writeValueAsString(stored);
        } catch (JacksonException exception) {
            throw new InvalidMediaMetadataResultException(
                    "Could not serialize media metadata result", exception);
        }
    }

    public MediaMetadataResult read(String resultJson) {
        try {
            JsonNode stored = jsonMapper.readTree(Objects.requireNonNull(resultJson, "resultJson"));
            requireObject(stored, "Stored result");
            int version = requiredInt(stored, "version");
            if (version != MediaMetadataResult.CURRENT_VERSION) {
                throw new IllegalArgumentException("Unsupported media metadata result version: " + version);
            }
            MediaMetadataOutcome outcome = requiredOutcome(stored, "outcome");
            return switch (outcome) {
                case AVAILABLE -> readAvailable(stored, version);
                case UNSUPPORTED -> readUnsupported(stored, version);
            };
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new InvalidMediaMetadataResultException("Invalid media metadata result", exception);
        }
    }

    private void writeAvailable(ObjectNode stored, AvailableMediaMetadata available) {
        stored.put("mediaKind", available.mediaKind().name());
        if (available.mediaKind() == MediaKind.IMAGE) {
            ImageMediaMetadata image = available.image();
            ObjectNode payload = stored.putObject("image");
            payload.put("format", image.format());
            payload.put("width", image.width());
            payload.put("height", image.height());
            return;
        }

        VideoMediaMetadata video = available.video();
        ObjectNode payload = stored.putObject("video");
        var formats = payload.putArray("containerFormats");
        video.containerFormats().forEach(formats::add);
        if (video.durationMicros() == null) {
            payload.putNull("durationMicros");
        } else {
            payload.put("durationMicros", video.durationMicros());
        }
        payload.put("videoCodec", video.videoCodec());
        payload.put("width", video.width());
        payload.put("height", video.height());
        payload.put("videoStreamCount", video.videoStreamCount());
        if (video.audioCodec() == null) {
            payload.putNull("audioCodec");
        } else {
            payload.put("audioCodec", video.audioCodec());
        }
        payload.put("audioStreamCount", video.audioStreamCount());
    }

    private static AvailableMediaMetadata readAvailable(JsonNode stored, int version) {
        requireExactFields(stored, Set.of("version", "outcome", "mediaKind", "image"),
                Set.of("version", "outcome", "mediaKind", "video"));
        MediaKind mediaKind = requiredMediaKind(stored, "mediaKind");
        return switch (mediaKind) {
            case IMAGE -> new AvailableMediaMetadata(version, mediaKind, readImage(stored.get("image")), null);
            case VIDEO -> new AvailableMediaMetadata(version, mediaKind, null, readVideo(stored.get("video")));
        };
    }

    private static UnsupportedMediaMetadata readUnsupported(JsonNode stored, int version) {
        requireExactFields(stored, Set.of("version", "outcome"));
        return new UnsupportedMediaMetadata(version);
    }

    private static ImageMediaMetadata readImage(JsonNode image) {
        requireObject(image, "Image payload");
        requireExactFields(image, Set.of("format", "width", "height"));
        return new ImageMediaMetadata(
                requiredString(image, "format"),
                requiredInt(image, "width"),
                requiredInt(image, "height"));
    }

    private static VideoMediaMetadata readVideo(JsonNode video) {
        requireObject(video, "Video payload");
        requireExactFields(video, Set.of(
                "containerFormats", "durationMicros", "videoCodec", "width", "height",
                "videoStreamCount", "audioCodec", "audioStreamCount"));
        return new VideoMediaMetadata(
                requiredStringArray(video, "containerFormats"),
                nullableLong(video, "durationMicros"),
                requiredString(video, "videoCodec"),
                requiredInt(video, "width"),
                requiredInt(video, "height"),
                requiredLong(video, "videoStreamCount"),
                nullableString(video, "audioCodec"),
                requiredLong(video, "audioStreamCount"));
    }

    private static MediaMetadataOutcome requiredOutcome(JsonNode object, String fieldName) {
        try {
            return MediaMetadataOutcome.valueOf(requiredString(object, fieldName));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported media metadata outcome", exception);
        }
    }

    private static MediaKind requiredMediaKind(JsonNode object, String fieldName) {
        try {
            return MediaKind.valueOf(requiredString(object, fieldName));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported media kind", exception);
        }
    }

    private static int requiredInt(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(fieldName + " must be an integer within range");
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(fieldName + " must be a long integer within range");
        }
        return value.longValue();
    }

    private static Long nullableLong(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(fieldName + " must be null or a long integer within range");
        }
        return value.longValue();
    }

    private static String requiredString(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static String nullableString(JsonNode object, String fieldName) {
        JsonNode value = requiredField(object, fieldName);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must be null or a non-empty string");
        }
        return value.textValue();
    }

    private static List<String> requiredStringArray(JsonNode object, String fieldName) {
        JsonNode values = requiredField(object, fieldName);
        if (!values.isArray()) {
            throw new IllegalArgumentException(fieldName + " must be an array");
        }
        var result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must contain non-empty strings");
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static JsonNode requiredField(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        return value;
    }

    private static void requireObject(JsonNode value, String description) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(description + " must be an object");
        }
    }

    @SafeVarargs
    private static void requireExactFields(JsonNode object, Set<String>... supportedShapes) {
        var actual = new HashSet<String>();
        object.properties().forEach(property -> actual.add(property.getKey()));
        for (Set<String> supportedShape : supportedShapes) {
            if (actual.equals(supportedShape)) {
                return;
            }
        }
        throw new IllegalArgumentException("Stored result does not match a supported schema shape");
    }
}
