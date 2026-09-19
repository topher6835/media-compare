package io.github.topher6835.mediacompare.location;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Strict codec for the version-1 binary location equality key. */
public final class LocationKeyCodec {
    public static final int MAX_PAYLOAD_BYTES = 16 * 1_024;
    private static final String PREFIX = "lk1:";
    private static final int FIXED_BYTES = 1 + Integer.BYTES + Integer.BYTES;
    private static final HexFormat HEX = HexFormat.of();

    private LocationKeyCodec() {
    }

    public static LocationKey encode(LocationPath path) {
        Objects.requireNonNull(path, "path");
        List<byte[]> roots = encodeFields(path.rootFields(), "Location root field");
        List<byte[]> components = encodeFields(path.components(), "Location path component");

        long size = FIXED_BYTES;
        for (byte[] field : roots) {
            size += Integer.BYTES + field.length;
        }
        for (byte[] field : components) {
            size += Integer.BYTES + field.length;
        }
        if (size > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Location key payload exceeds 16 KiB");
        }

        ByteBuffer payload = ByteBuffer.allocate(Math.toIntExact(size));
        payload.put((byte) path.dialect().binaryId());
        writeFields(payload, roots);
        writeFields(payload, components);
        return LocationKey.canonical(PREFIX + HEX.formatHex(payload.array()));
    }

    public static LocationPath decode(LocationKey key) {
        return decode(Objects.requireNonNull(key, "key").value());
    }

    public static LocationPath decode(String keyText) {
        Objects.requireNonNull(keyText, "Location key is required");
        if (!keyText.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported location key version");
        }
        String hex = keyText.substring(PREFIX.length());
        if ((hex.length() & 1) != 0 || hex.length() > MAX_PAYLOAD_BYTES * 2
                || !hex.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("Location key must contain bounded lowercase hexadecimal payload");
        }

        final byte[] bytes;
        try {
            bytes = HEX.parseHex(hex);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Location key contains malformed hexadecimal payload", exception);
        }
        if (bytes.length < FIXED_BYTES) {
            throw new IllegalArgumentException("Location key payload is truncated");
        }

        ByteBuffer payload = ByteBuffer.wrap(bytes);
        LocationDialect dialect = LocationDialect.fromBinaryId(Byte.toUnsignedInt(payload.get()));
        List<String> roots = readFields(payload, 2, "root field");
        List<String> components = readFields(
                payload, LocationPathValidation.MAX_COMPONENT_COUNT, "component");
        if (payload.hasRemaining()) {
            throw new IllegalArgumentException("Location key contains trailing payload bytes");
        }

        LocationPath path = new LocationPath(dialect, roots, components);
        if (!encode(path).value().equals(keyText)) {
            throw new IllegalArgumentException("Location key is not canonical");
        }
        return path;
    }

    public static boolean matches(LocationPath path, LocationKey key) {
        return encode(path).equals(Objects.requireNonNull(key, "key"));
    }

    private static List<byte[]> encodeFields(List<String> fields, String description) {
        var encoded = new ArrayList<byte[]>(fields.size());
        for (String field : fields) {
            encoded.add(LocationPathValidation.utf8(field, description));
        }
        return encoded;
    }

    private static void writeFields(ByteBuffer payload, List<byte[]> fields) {
        payload.putInt(fields.size());
        for (byte[] field : fields) {
            payload.putInt(field.length);
            payload.put(field);
        }
    }

    private static List<String> readFields(ByteBuffer payload, int maximumCount, String description) {
        long count = readUnsignedInt(payload, description + " count");
        if (count > maximumCount) {
            throw new IllegalArgumentException("Location key " + description + " count exceeds its limit");
        }
        var fields = new ArrayList<String>((int) count);
        for (int index = 0; index < count; index++) {
            long length = readUnsignedInt(payload, description + " length");
            if (length > LocationPathValidation.MAX_FIELD_UTF8_BYTES || length > payload.remaining()) {
                throw new IllegalArgumentException("Location key " + description + " has an invalid length");
            }
            byte[] bytes = new byte[(int) length];
            payload.get(bytes);
            fields.add(decodeUtf8(bytes, description));
        }
        return List.copyOf(fields);
    }

    private static long readUnsignedInt(ByteBuffer payload, String description) {
        if (payload.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Location key is truncated before " + description);
        }
        return Integer.toUnsignedLong(payload.getInt());
    }

    private static String decodeUtf8(byte[] bytes, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Location key " + description + " is not strict UTF-8", exception);
        }
    }
}
