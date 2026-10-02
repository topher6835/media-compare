package io.github.topher6835.mediacompare.filesystem;

import java.util.Locale;

/** Runtime classification only; never replaces persisted continuity evidence. */
public enum FileSystemProfile {
    APFS, NTFS, EXFAT, UNSUPPORTED, UNKNOWN;

    public static FileSystemProfile fromType(String type) {
        if (type == null || type.isBlank()) return UNKNOWN;
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "APFS" -> APFS;
            case "NTFS" -> NTFS;
            case "EXFAT" -> EXFAT;
            default -> UNSUPPORTED;
        };
    }
}
