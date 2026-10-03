package io.github.topher6835.mediacompare.filesystem;

import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Exact immutable schemas with deterministic ordering, following the Slice 2 receipt codec. */
public final class WindowsExfatEvidenceCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = 128 * 1024;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            })
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    public String encode(WindowsExfatContextEvidence value) { return write(value); }
    public String encode(WindowsExfatSourceEvidence value) { return write(value); }
    public WindowsExfatContextEvidence decodeContext(String document) {
        return read(document, WindowsExfatContextEvidence.class);
    }
    public WindowsExfatSourceEvidence decodeSource(String document) {
        return read(document, WindowsExfatSourceEvidence.class);
    }

    private String write(Object value) {
        if (value == null) throw new IllegalArgumentException("Evidence is required");
        try {
            String document = json.writeValueAsString(value);
            bounded(document);
            return document;
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("Invalid exFAT evidence", failure);
        }
    }

    private <T> T read(String document, Class<T> type) {
        bounded(document);
        try {
            T value = json.readValue(document, type);
            write(value); // Also validates escaped Unicode after decoding.
            return value;
        } catch (JacksonException | NullPointerException | ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid exFAT evidence", failure);
        }
    }

    private static void bounded(String document) {
        if (document == null || document.isEmpty() || document.length() > MAX_DOCUMENT_UTF8_BYTES
                || document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_UTF8_BYTES) {
            throw new IllegalArgumentException("ExFAT evidence exceeds document bounds");
        }
        for (int i = 0; i < document.length(); i++) {
            char c = document.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= document.length() || !Character.isLowSurrogate(document.charAt(++i))) {
                    throw new IllegalArgumentException("Malformed evidence Unicode");
                }
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed evidence Unicode");
        }
    }
}
