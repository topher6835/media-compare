package io.github.topher6835.mediacompare.location;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

final class EvidenceValues {
    static final int MAX_DIAGNOSTIC_UTF8_BYTES = 1_024;
    private static final BigInteger MAX_UNSIGNED_LONG = new BigInteger("18446744073709551615");

    private EvidenceValues() {
    }

    static String canonicalUuid(String value, String description) {
        Objects.requireNonNull(value, description);
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException(description + " must be canonical UUID text");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(description + " must be canonical UUID text", exception);
        }
    }

    static String unsignedDecimal(String value, boolean allowZero, String description) {
        Objects.requireNonNull(value, description);
        if (value.length() > 20 || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(description + " must be canonical unsigned decimal text");
        }
        BigInteger number = new BigInteger(value);
        if ((!allowZero && number.signum() == 0) || number.compareTo(MAX_UNSIGNED_LONG) > 0) {
            throw new IllegalArgumentException(description + " is outside its supported unsigned range");
        }
        return value;
    }

    static long nonnegative(long value, String description) {
        if (value < 0) {
            throw new IllegalArgumentException(description + " must not be negative");
        }
        return value;
    }

    static String optionalDiagnostic(String value, String description) {
        if (value == null) {
            return null;
        }
        requireWellFormedUnicode(value, description);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_DIAGNOSTIC_UTF8_BYTES) {
            throw new IllegalArgumentException(description + " must be nonblank and at most 1 KiB of UTF-8");
        }
        return value;
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

    static void requirePathAndKey(LocationPath path, LocationKey key, String description) {
        Objects.requireNonNull(path, description + " path");
        Objects.requireNonNull(key, description + " key");
        if (path.dialect() != LocationDialect.UNIX) {
            throw new IllegalArgumentException(description + " must use the Unix dialect");
        }
        if (!LocationKeyCodec.matches(path, key)) {
            throw new IllegalArgumentException(description + " path and key do not match");
        }
    }
}
