package io.github.topher6835.mediacompare.preview;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned, length-prefixed UTF-8 and big-endian integer encoding; no locale or path input. */
public final class PreviewAssetKey {
    private PreviewAssetKey() {
    }

    public static String compute(PreviewSourceEvidence evidence, PreviewKind kind, PreviewDefinition definition) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(definition, "definition");
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            writeText(output, "preview-cache-key-v1");
            output.writeLong(evidence.fileEntryId());
            output.writeLong(evidence.contentRecordId());
            output.writeLong(evidence.observationRevision());
            output.writeLong(evidence.sizeBytes());
            output.writeLong(evidence.modifiedTimeEpochSecond());
            output.writeInt(evidence.modifiedTimeNano());
            writeText(output, kind.name());
            writeText(output, definition.generatorId());
            writeText(output, definition.generatorVersion());
            output.writeLong(definition.configurationVersion());
            writeText(output, definition.configurationHash());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK preview key encoding unavailable", exception);
        }
    }

    public static void requireValid(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("assetKey must be 64 lowercase SHA-256 hex characters");
        }
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
