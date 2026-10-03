package io.github.topher6835.mediacompare.filesystem;

import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Dispatches known Windows wire formats only; the selected strict codec validates the full envelope. */
public enum WindowsDurableEvidenceFormat {
    NTFS_CONTEXT, NTFS_SOURCE, EXFAT_CONTEXT, EXFAT_SOURCE, UNKNOWN, MALFORMED;

    private static final int MAX_BYTES = 128 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public static WindowsDurableEvidenceFormat identify(String document) {
        if (document == null || document.length() > MAX_BYTES
                || document.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return MALFORMED;
        try {
            var envelope = JSON.readTree(document);
            if (envelope == null) return MALFORMED;
            if (envelope.isArray()) {
                var version = envelope.get(0);
                if (version == null || !version.isTextual()) return MALFORMED;
                return switch (version.asText()) {
                    case "windows-ntfs-context-v1" -> NTFS_CONTEXT;
                    case "windows-ntfs-source-v1" -> NTFS_SOURCE;
                    default -> UNKNOWN;
                };
            }
            if (envelope.isObject()) {
                var version = envelope.get("version");
                if (version == null || !version.isTextual()) return MALFORMED;
                return switch (version.asText()) {
                    case WindowsExfatContextEvidence.VERSION -> EXFAT_CONTEXT;
                    case WindowsExfatSourceEvidence.VERSION -> EXFAT_SOURCE;
                    default -> UNKNOWN;
                };
            }
            return MALFORMED;
        } catch (JacksonException failure) {
            return MALFORMED;
        }
    }

    public static FileSystemProfile sourceProfile(String document) {
        return switch (identify(document)) {
            case NTFS_SOURCE -> FileSystemProfile.NTFS;
            case EXFAT_SOURCE -> FileSystemProfile.EXFAT;
            default -> throw new IllegalArgumentException("Invalid or unknown Windows Source evidence format");
        };
    }

    public static FileSystemProfile contextProfile(String document) {
        return switch (identify(document)) {
            case NTFS_CONTEXT -> FileSystemProfile.NTFS;
            case EXFAT_CONTEXT -> FileSystemProfile.EXFAT;
            default -> throw new IllegalArgumentException("Invalid or unknown Windows context evidence format");
        };
    }
}
