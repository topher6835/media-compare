package io.github.topher6835.mediacompare.location;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class LocationPathValidation {
    static final int MAX_COMPONENT_COUNT = 1_024;
    static final int MAX_FIELD_UTF8_BYTES = 4 * 1_024;

    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL", "CLOCK$");

    private LocationPathValidation() {
    }

    static void validate(LocationDialect dialect, List<String> rootFields, List<String> components) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(rootFields, "rootFields");
        Objects.requireNonNull(components, "components");
        if (components.size() > MAX_COMPONENT_COUNT) {
            throw new IllegalArgumentException("Location path has too many components");
        }

        switch (dialect) {
            case UNIX -> {
                if (!rootFields.isEmpty()) {
                    throw new IllegalArgumentException("Unix locations must not have root fields");
                }
            }
            case WINDOWS_DRIVE -> {
                if (rootFields.size() != 1 || !isAsciiLetter(rootFields.getFirst())) {
                    throw new IllegalArgumentException("Windows drive locations require one drive-letter root field");
                }
            }
            case WINDOWS_UNC -> {
                if (rootFields.size() != 2) {
                    throw new IllegalArgumentException("UNC locations require server and share root fields");
                }
                rootFields.forEach(LocationPathValidation::validateWindowsField);
            }
        }

        for (String component : components) {
            if (dialect == LocationDialect.UNIX) {
                validateUnixComponent(component);
            } else {
                validateWindowsField(component);
            }
        }
    }

    static void requireWellFormedUnicode(String value, String description) {
        Objects.requireNonNull(value, description);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(description + " contains malformed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(description + " contains malformed Unicode");
            }
        }
    }

    static byte[] utf8(String value, String description) {
        requireWellFormedUnicode(value, description);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_UTF8_BYTES) {
            throw new IllegalArgumentException(description + " exceeds the UTF-8 length limit");
        }
        return bytes;
    }

    private static void validateUnixComponent(String value) {
        validateCommonField(value);
        if (value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Unix path component contains a separator");
        }
    }

    private static void validateWindowsField(String value) {
        validateCommonField(value);
        if (value.endsWith(".") || value.endsWith(" ")) {
            throw new IllegalArgumentException("Windows path component has a forbidden trailing character");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || "<>:\"/\\|?*".indexOf(character) >= 0) {
                throw new IllegalArgumentException("Windows path component contains a forbidden character");
            }
        }

        String stem = value;
        int dot = stem.indexOf('.');
        if (dot >= 0) {
            stem = stem.substring(0, dot);
        }
        String upperStem = stem.toUpperCase(Locale.ROOT);
        if (RESERVED_WINDOWS_NAMES.contains(upperStem)
                || upperStem.matches("COM[1-9]")
                || upperStem.matches("LPT[1-9]")) {
            throw new IllegalArgumentException("Windows path component uses a reserved device name");
        }
    }

    private static void validateCommonField(String value) {
        Objects.requireNonNull(value, "Location path field is required");
        requireWellFormedUnicode(value, "Location path field");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Location path fields must not be empty");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Dot path components are not supported");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Location path fields must not contain NUL");
        }
        utf8(value, "Location path field");
    }

    private static boolean isAsciiLetter(String value) {
        return value != null && value.length() == 1
                && ((value.charAt(0) >= 'A' && value.charAt(0) <= 'Z')
                        || (value.charAt(0) >= 'a' && value.charAt(0) <= 'z'));
    }
}
