package io.github.topher6835.mediacompare.location;

import java.util.Arrays;

/** Supported, host-independent filesystem address grammars. */
public enum LocationDialect {
    UNIX("unix", 0x01),
    WINDOWS_DRIVE("win-drive", 0x02),
    WINDOWS_UNC("win-unc", 0x03);

    private final String persistedName;
    private final int binaryId;

    LocationDialect(String persistedName, int binaryId) {
        this.persistedName = persistedName;
        this.binaryId = binaryId;
    }

    public String persistedName() {
        return persistedName;
    }

    public int binaryId() {
        return binaryId;
    }

    public static LocationDialect fromPersistedName(String value) {
        return Arrays.stream(values())
                .filter(dialect -> dialect.persistedName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported location dialect: " + value));
    }

    public static LocationDialect fromBinaryId(int value) {
        return Arrays.stream(values())
                .filter(dialect -> dialect.binaryId == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported location dialect ID: " + value));
    }
}
