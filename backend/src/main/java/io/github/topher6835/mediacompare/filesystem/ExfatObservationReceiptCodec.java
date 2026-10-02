package io.github.topher6835.mediacompare.filesystem;

import java.nio.charset.StandardCharsets;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict JSON schema and deterministic ordering; never repairs or truncates a receipt. */
public final class ExfatObservationReceiptCodec {
    public static final int MAX_DOCUMENT_UTF8_BYTES = 1024 * 1024;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    public String encode(ExfatObservationReceipt receipt) {
        if (receipt == null) throw new IllegalArgumentException("Receipt is required");
        try {
            String document = json.writeValueAsString(receipt);
            requireBounded(document);
            return document;
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("Cannot encode exFAT observation receipt", failure);
        }
    }

    public ExfatObservationReceipt decode(String document) {
        requireBounded(document);
        try {
            ExfatObservationReceipt receipt = json.readValue(document, ExfatObservationReceipt.class);
            if (receipt == null) throw new IllegalArgumentException("Receipt must be an object");
            // Escaped malformed surrogate values must be rejected too, including nested text.
            requireBounded(encode(receipt));
            return receipt;
        } catch (JacksonException | NullPointerException | ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid exFAT observation receipt", failure);
        }
    }

    private static void requireBounded(String document) {
        if (document == null || document.isEmpty() || document.length() > MAX_DOCUMENT_UTF8_BYTES
                || document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_UTF8_BYTES) {
            throw new IllegalArgumentException("Receipt JSON must contain 1..1048576 UTF-8 bytes");
        }
        for (int i = 0; i < document.length(); i++) {
            char character = document.charAt(i);
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= document.length() || !Character.isLowSurrogate(document.charAt(i + 1))) {
                    throw new IllegalArgumentException("Malformed receipt JSON Unicode");
                }
                i++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Malformed receipt JSON Unicode");
            }
        }
    }
}
