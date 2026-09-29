package io.github.topher6835.mediacompare.preview;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

final class ThumbnailImageFixtures {
    private ThumbnailImageFixtures() {
    }

    static BufferedImage pattern(int width, int height, boolean alpha) {
        var image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = x < width / 2 ? (y < height / 2 ? 0xffee1010 : 0xff1010ee)
                        : (y < height / 2 ? 0xff10ee10 : 0xffeeee10);
                image.setRGB(x, y, alpha ? color & 0x60ffffff : color);
            }
        }
        return image;
    }

    static byte[] encoded(String format, int width, int height, boolean alpha) throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(pattern(width, height, alpha), format, output)) {
            throw new IOException("Test ImageIO writer unavailable");
        }
        return output.toByteArray();
    }

    static byte[] withOrientation(byte[] jpeg, int orientation, ByteOrder order) throws IOException {
        byte[] app1 = exif(orientation, order);
        var output = new ByteArrayOutputStream();
        output.write(jpeg, 0, 2);
        output.write(0xff);
        output.write(0xe1);
        output.write((app1.length + 2) >> 8);
        output.write((app1.length + 2) & 0xff);
        output.write(app1);
        output.write(jpeg, 2, jpeg.length - 2);
        return output.toByteArray();
    }

    static byte[] exif(int orientation, ByteOrder order) {
        var bytes = ByteBuffer.allocate(32).order(order);
        bytes.put(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        bytes.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        bytes.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        bytes.putShort((short) 42).putInt(8).putShort((short) 1);
        bytes.putShort((short) 0x0112).putShort((short) 3).putInt(1);
        bytes.putShort((short) orientation).putShort((short) 0).putInt(0);
        return bytes.array();
    }

    static byte[] oversizedPngHeader() throws IOException {
        byte[] bytes = encoded("PNG", 1, 1, false);
        // Replace IHDR width and its CRC without allocating an oversized raster.
        ByteBuffer.wrap(bytes).putInt(16, SmallThumbnailDefinition.MAX_ENCODED_EDGE + 1);
        var crc = new java.util.zip.CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        return bytes;
    }

    static Path write(Path directory, String name, byte[] bytes) throws IOException {
        return Files.write(directory.resolve(name), bytes);
    }
}
