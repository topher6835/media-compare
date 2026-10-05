package io.github.topher6835.mediacompare.preview;

import io.github.topher6835.mediacompare.contentread.JdkOriginalImageReaders;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

@Component
public class SmallThumbnailRenderer {
    public record Dimensions(int width, int height) {
    }

    public Optional<Dimensions> render(Path source, Path temporaryOutput) throws IOException {
        try (var input = new PreviewImageInput(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            return render(input, readers.next(), temporaryOutput);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalidImage) {
            throw new IOException("Invalid image data/dimensions", invalidImage);
        }
    }

    public Optional<Dimensions> render(javax.imageio.stream.ImageInputStream input, Path temporaryOutput) throws IOException {
        var selected = JdkOriginalImageReaders.select(input);
        if (selected.isEmpty()) return Optional.empty();
        return render(input, selected.get(), temporaryOutput);
    }

    private Optional<Dimensions> render(javax.imageio.stream.ImageInputStream input, ImageReader reader,
            Path temporaryOutput) throws IOException {
        try {
            String format = reader.getFormatName().toUpperCase(Locale.ROOT);
            boolean jpeg = format.equals("JPEG") || format.equals("JPG");
            if (!jpeg && !format.equals("PNG")) {
                return Optional.empty();
            }
            int orientation = jpeg ? JpegExifOrientation.read(input) : 1;
            reader.setInput(input, false, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (Math.max(width, height) > SmallThumbnailDefinition.MAX_ENCODED_EDGE) {
                // PNG decoding retains full-width scanline buffers even when subsampling.
                throw new IOException("Encoded image exceeds safe decoder edge limit");
            }
            int sample = subsampling(width, height);
            long decodedWidth = ((long) width + sample - 1) / sample;
            long decodedHeight = ((long) height + sample - 1) / sample;
            if (decodedWidth * decodedHeight > (long) SmallThumbnailDefinition.DECODE_EDGE
                    * SmallThumbnailDefinition.DECODE_EDGE) {
                throw new IOException("Thumbnail intermediate exceeds pixel limit");
            }
            var parameters = reader.getDefaultReadParam();
            parameters.setSourceSubsampling(sample, sample, 0, 0);
            BufferedImage decoded = reader.read(0, parameters);
            if (decoded == null || decoded.getWidth() != decodedWidth || decoded.getHeight() != decodedHeight) {
                throw new IOException("Image reader did not honor bounded subsampling");
            }
            BufferedImage oriented = orient(decoded, orientation);
            Dimensions dimensions = dimensions(width, height, orientation);
            BufferedImage output = new BufferedImage(dimensions.width(), dimensions.height(),
                    decoded.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                graphics.drawImage(oriented, 0, 0, dimensions.width(), dimensions.height(), null);
            } finally {
                graphics.dispose();
            }
            try (var stream = Files.newOutputStream(temporaryOutput, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                    var imageOutput = new MemoryCacheImageOutputStream(stream)) {
                // The bounded output stream avoids ImageIO's default OS temporary-file spool.
                if (!ImageIO.write(output, SmallThumbnailDefinition.EXTENSION, imageOutput)) {
                    throw new IOException("JDK PNG writer unavailable");
                }
            }
            return Optional.of(dimensions);
        } finally {
            reader.dispose();
        }
    }

    public static int subsampling(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Encoded dimensions must be positive");
        }
        return (int) Math.max(1, ((long) Math.max(width, height)
                + SmallThumbnailDefinition.DECODE_EDGE - 1) / SmallThumbnailDefinition.DECODE_EDGE);
    }

    static Dimensions dimensions(int width, int height, int orientation) {
        int displayedWidth = orientation >= 5 ? height : width;
        int displayedHeight = orientation >= 5 ? width : height;
        double scale = Math.min(1.0, (double) SmallThumbnailDefinition.MAX_EDGE / Math.max(width, height));
        return new Dimensions(Math.max(1, (int) Math.round(displayedWidth * scale)),
                Math.max(1, (int) Math.round(displayedHeight * scale)));
    }

    /** Exact pixel permutation before final resize, including both diagonal reflections. */
    static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation < 1 || orientation > 8) {
            throw new IllegalArgumentException("EXIF orientation must be 1..8");
        }
        if (orientation == 1) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage result = new BufferedImage(orientation >= 5 ? height : width,
                orientation >= 5 ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX = switch (orientation) {
                    case 2, 3 -> width - 1 - x;
                    case 4 -> x;
                    case 5, 8 -> y;
                    case 6, 7 -> height - 1 - y;
                    default -> x;
                };
                int targetY = switch (orientation) {
                    case 2 -> y;
                    case 3, 4 -> height - 1 - y;
                    case 5, 6 -> x;
                    case 7, 8 -> width - 1 - x;
                    default -> y;
                };
                result.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return result;
    }
}
