package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.preview.ThumbnailImageFixtures.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JpegExifOrientationTests {
    @ParameterizedTest
    @ValueSource(ints = {1,2,3,4,5,6,7,8})
    void parsesBothByteOrdersAndRestoresInputPosition(int orientation) throws Exception {
        for (var order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] bytes = withOrientation(encoded("JPEG", 3, 2, false), orientation, order);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                input.seek(2);
                assertEquals(orientation, JpegExifOrientation.read(input));
                assertEquals(2, input.getStreamPosition());
            }
        }
    }

    @Test
    void defaultsForAbsentExifAndNonJpeg() throws Exception {
        assertEquals(1, parse(encoded("JPEG", 3, 2, false)));
        assertEquals(1, parse(encoded("PNG", 3, 2, false)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"truncated", "offset", "count", "magic", "byte-order", "tag-type", "tag-count", "value"})
    void malformedMetadataDefaultsSafely(String mutation) throws Exception {
        byte[] bytes = withOrientation(encoded("JPEG", 3, 2, false), 6, ByteOrder.LITTLE_ENDIAN);
        // TIFF starts at byte 12: SOI + APP1 marker/length + EXIF signature.
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        switch (mutation) {
            case "truncated" -> bytes = Arrays.copyOf(bytes, 22);
            case "offset" -> data.putInt(16, -1);
            case "count" -> data.putShort(20, (short) 65535);
            case "magic" -> data.putShort(14, (short) 43);
            case "byte-order" -> bytes[12] = 'Z';
            case "tag-type" -> data.putShort(24, (short) 4);
            case "tag-count" -> data.putInt(26, Integer.MAX_VALUE);
            case "value" -> data.putShort(30, (short) 9);
            default -> throw new IllegalArgumentException(mutation);
        }
        assertEquals(1, parse(bytes));
    }

    @Test
    void boundedHeaderScanHandlesRepeatedMarkersAndTruncatedLengths() throws Exception {
        byte[] fill = new byte[1_048_600];
        Arrays.fill(fill, (byte) 0xff);
        fill[1] = (byte) 0xd8;
        assertEquals(1, parse(fill));
        assertEquals(1, parse(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0}));
    }

    private int parse(byte[] bytes) throws Exception {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            return JpegExifOrientation.read(input);
        }
    }
}
