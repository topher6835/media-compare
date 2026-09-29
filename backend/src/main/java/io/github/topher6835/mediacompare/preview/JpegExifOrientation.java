package io.github.topher6835.mediacompare.preview;

import java.io.IOException;
import javax.imageio.stream.ImageInputStream;

/** Orientation only: at most 1 MiB of JPEG headers and one <= 65533-byte APP1 segment. */
public final class JpegExifOrientation {
    private static final long HEADER_LIMIT = 1_048_576;

    private JpegExifOrientation() {
    }

    /** Malformed/truncated metadata and absent/invalid orientation default to 1. Restores position. */
    public static int read(ImageInputStream input) throws IOException {
        long position = input.getStreamPosition();
        try {
            input.seek(0);
            if (input.read() != 0xff || input.read() != 0xd8) {
                return 1;
            }
            while (input.getStreamPosition() < HEADER_LIMIT) {
                if (input.read() != 0xff) {
                    return 1;
                }
                int marker;
                do {
                    marker = input.read();
                } while (marker == 0xff && input.getStreamPosition() < HEADER_LIMIT);
                if (marker < 0 || marker == 0xda || marker == 0xd9 || marker == 0) {
                    return 1;
                }
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                    continue;
                }
                int high = input.read();
                int low = input.read();
                int length = (high << 8) | low;
                if (high < 0 || low < 0 || length < 2
                        || input.getStreamPosition() + length - 2 > HEADER_LIMIT) {
                    return 1;
                }
                if (marker == 0xe1) {
                    byte[] segment = new byte[length - 2];
                    input.readFully(segment);
                    int orientation = parse(segment);
                    if (orientation != 0) {
                        return orientation;
                    }
                } else {
                    input.seek(input.getStreamPosition() + length - 2);
                }
            }
            return 1;
        } catch (java.io.EOFException truncated) {
            return 1;
        } finally {
            input.seek(position);
        }
    }

    // Returns 0 for a non-EXIF APP1; a malformed EXIF block defaults to 1.
    private static int parse(byte[] bytes) {
        if (bytes.length < 6 || bytes[0] != 'E' || bytes[1] != 'x' || bytes[2] != 'i'
                || bytes[3] != 'f' || bytes[4] != 0 || bytes[5] != 0) {
            return 0;
        }
        int base = 6;
        if (bytes.length < base + 8) {
            return 1;
        }
        boolean little = bytes[base] == 'I' && bytes[base + 1] == 'I';
        boolean big = bytes[base] == 'M' && bytes[base + 1] == 'M';
        if ((!little && !big) || number(bytes, base + 2, 2, little) != 42) {
            return 1;
        }
        long offset = number(bytes, base + 4, 4, little);
        long ifd = base + offset;
        if (offset < 8 || ifd > bytes.length - 2L) {
            return 1;
        }
        int count = (int) number(bytes, (int) ifd, 2, little);
        long entries = ifd + 2;
        if (entries + count * 12L + 4 > bytes.length) {
            return 1;
        }
        for (int index = 0; index < count; index++) {
            int entry = (int) (entries + index * 12L);
            if (number(bytes, entry, 2, little) == 0x0112) {
                if (number(bytes, entry + 2, 2, little) != 3
                        || number(bytes, entry + 4, 4, little) != 1) {
                    return 1;
                }
                int value = (int) number(bytes, entry + 8, 2, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static long number(byte[] bytes, int offset, int length, boolean little) {
        long value = 0;
        for (int index = 0; index < length; index++) {
            int at = little ? offset + length - index - 1 : offset + index;
            value = (value << 8) | (bytes[at] & 0xff);
        }
        return value;
    }
}
