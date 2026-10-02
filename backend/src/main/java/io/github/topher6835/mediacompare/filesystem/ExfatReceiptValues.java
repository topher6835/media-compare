package io.github.topher6835.mediacompare.filesystem;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

final class ExfatReceiptValues {
    private static final LocationPathCodec PATHS = new LocationPathCodec();
    private ExfatReceiptValues() { }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static void uuid(String value) {
        UUID uuid = canonicalUuid(value);
        require(uuid.variant() == 2 && uuid.version() == 4, "Expected IETF version 4 UUID");
    }

    private static UUID canonicalUuid(String value) {
        require(value != null, "Expected canonical lowercase UUID");
        UUID uuid = UUID.fromString(value);
        require(value.equals(uuid.toString()), "Expected canonical lowercase UUID");
        return uuid;
    }

    static void nonnegative(long... values) {
        for (long value : values) require(value >= 0, "Expected nonnegative receipt value");
    }

    static void positive(long... values) {
        for (long value : values) require(value > 0, "Expected positive receipt ID/generation");
    }

    static Instant instant(long seconds, int nanos) {
        require(nanos >= 0 && nanos <= 999999999, "Invalid exact mtime nanoseconds");
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (DateTimeException failure) {
            throw new IllegalArgumentException("Invalid exact mtime", failure);
        }
    }

    static LocationPath path(String json, String key) {
        text(json, LocationPathCodec.MAX_DOCUMENT_UTF8_BYTES);
        LocationPath path = PATHS.decode(json);
        require(path.dialect() == LocationDialect.WINDOWS_DRIVE && PATHS.encode(path).equals(json)
                && LocationKeyCodec.decode(key).equals(path), "Noncanonical exFAT lp1/lk1 route");
        return path;
    }

    static LocationPath finalRoute(String value) {
        text(value, 64 * 1024);
        require(value.startsWith("\\\\?\\") && value.length() > 7, "Expected native drive final route");
        String ordinary = value.substring(4);
        LocationPath path = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, ordinary);
        String expected = path.rootFields().getFirst() + ":\\" + String.join("\\", path.components());
        require(ordinary.equals(expected), "Noncanonical native final route");
        return path;
    }

    static void volumeGuid(String value) {
        text(value, 64);
        String prefix = "\\\\?\\Volume{";
        require(value.startsWith(prefix) && value.endsWith("}\\"), "Invalid volume GUID route");
        // OS-assigned volume routes are not application-issued random UUIDs.
        canonicalUuid(value.substring(prefix.length(), value.length() - 2));
    }

    static void text(String value, int maxBytes) {
        require(value != null && !value.isBlank(), "Required receipt text is empty");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            require(character >= 0x20, "Receipt text contains control characters");
            if (Character.isHighSurrogate(character)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)),
                        "Malformed receipt Unicode");
                i++;
            } else require(!Character.isLowSurrogate(character), "Malformed receipt Unicode");
        }
        require(value.getBytes(StandardCharsets.UTF_8).length <= maxBytes, "Receipt text exceeds byte limit");
    }
}
