package io.github.topher6835.mediacompare.filesystem;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact, bounded NTFS evidence envelopes in the existing context/Source JSON columns. */
public final class WindowsNtfsEvidenceCodec {
    private static final String CONTEXT = "windows-ntfs-context-v1";
    private static final String SOURCE = "windows-ntfs-source-v1";
    private static final int MAX_BYTES = 128 * 1024;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final LocationPathCodec paths = new LocationPathCodec();

    public String encode(WindowsNtfsContextEvidence evidence) {
        return write(List.of(CONTEXT, evidence.contextId(), evidence.contextRevision(),
                paths.encode(evidence.anchor()), LocationKeyCodec.encode(evidence.anchor()).value(),
                evidence.identity().volumeSerial(), evidence.identity().fileId()));
    }

    public String encode(WindowsNtfsSourceEvidence evidence) {
        return write(List.of(SOURCE, evidence.sourceId(), evidence.sourceRevision(), evidence.contextId(),
                evidence.contextRevision(), paths.encode(evidence.root()),
                LocationKeyCodec.encode(evidence.root()).value(), evidence.identity().volumeSerial(),
                evidence.identity().fileId()));
    }

    public WindowsNtfsContextEvidence decodeContext(String document) {
        JsonNode node = read(document, CONTEXT, 7);
        return new WindowsNtfsContextEvidence(text(node, 1), number(node, 2),
                location(node, 3, 4), new WindowsNtfsIdentity(text(node, 5), text(node, 6)));
    }

    public WindowsNtfsSourceEvidence decodeSource(String document) {
        JsonNode node = read(document, SOURCE, 9);
        return new WindowsNtfsSourceEvidence(number(node, 1), number(node, 2), text(node, 3),
                number(node, 4), location(node, 5, 6),
                new WindowsNtfsIdentity(text(node, 7), text(node, 8)));
    }

    private LocationPath location(JsonNode node, int pathIndex, int keyIndex) {
        LocationPath path = paths.decode(text(node, pathIndex));
        if (!LocationKeyCodec.matches(path, LocationKey.parse(text(node, keyIndex)))) {
            throw new IllegalArgumentException("NTFS location and key disagree");
        }
        return path;
    }

    private String write(List<?> fields) {
        try {
            String document = json.writeValueAsString(fields);
            requireBounded(document);
            return document;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Cannot encode NTFS evidence", exception);
        }
    }

    private JsonNode read(String document, String type, int size) {
        try {
            requireBounded(document);
            JsonNode node = json.readTree(document);
            if (node == null || !node.isArray() || node.size() != size
                    || !type.equals(text(node, 0))) {
                throw new IllegalArgumentException("Invalid NTFS evidence envelope");
            }
            return node;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid NTFS evidence JSON", exception);
        }
    }

    private static String text(JsonNode node, int index) {
        JsonNode value = node.get(index);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Invalid NTFS evidence text");
        return value.asText();
    }

    private static long number(JsonNode node, int index) {
        JsonNode value = node.get(index);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("Invalid NTFS evidence number");
        }
        return value.longValue();
    }

    private static void requireBounded(String document) {
        if (document == null || document.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("NTFS evidence is missing or too large");
        }
    }
}
