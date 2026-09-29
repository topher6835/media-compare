package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.preview.ThumbnailImageFixtures.*;

import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SmallThumbnailRendererTests {
    @TempDir Path directory;
    private final SmallThumbnailRenderer renderer = new SmallThumbnailRenderer();

    @ParameterizedTest
    @CsvSource({"1000,500,320,160", "500,1000,160,320", "37,21,37,21", "1001,601,320,192"})
    void scalesJpegByContentWithOrientedAspectRatioAndNoUpscaling(int width, int height, int outWidth, int outHeight)
            throws Exception {
        Path source = write(directory, "misleading.bin", encoded("JPEG", width, height, false));
        Path output = Files.createFile(directory.resolve("output.tmp"));
        assertEquals(new SmallThumbnailRenderer.Dimensions(outWidth, outHeight), renderer.render(source, output).orElseThrow());
        var image = ImageIO.read(output.toFile());
        assertEquals(outWidth, image.getWidth());
        assertEquals(outHeight, image.getHeight());
        try (var input = new PreviewImageInput(output)) {
            var reader = ImageIO.getImageReaders(input).next();
            try { assertEquals("png", reader.getFormatName().toLowerCase(java.util.Locale.ROOT)); }
            finally { reader.dispose(); }
        }
    }

    @Test
    void retainsPngAlpha() throws Exception {
        Path source = write(directory, "alpha.png", encoded("PNG", 600, 300, true));
        Path output = Files.createFile(directory.resolve("output.tmp"));
        renderer.render(source, output);
        var image = ImageIO.read(output.toFile());
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0x60, image.getRGB(10, 10) >>> 24);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void appliesEveryOrientationAsAnExactPixelPermutation(int orientation) {
        var source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        for (int index = 0; index < 6; index++) source.setRGB(index % 3, index / 3, index + 1);
        int[][] expected = {
            {1,2,3,4,5,6}, {3,2,1,6,5,4}, {6,5,4,3,2,1}, {4,5,6,1,2,3},
            {1,4,2,5,3,6}, {4,1,5,2,6,3}, {6,3,5,2,4,1}, {3,6,2,5,1,4}
        };
        var result = SmallThumbnailRenderer.orient(source, orientation);
        assertEquals(orientation >= 5 ? 2 : 3, result.getWidth());
        assertEquals(orientation >= 5 ? 3 : 2, result.getHeight());
        for (int index = 0; index < 6; index++) {
            assertEquals(expected[orientation - 1][index],
                    result.getRGB(index % result.getWidth(), index / result.getWidth()) & 0xffffff);
        }
    }

    @ParameterizedTest
    @CsvSource({"1,1", "640,1", "641,2", "1280,2", "4000,7", "2147483647,3355444"})
    void boundsSubsamplingWithoutOverflowOrDecodingBelowTheRequiredEdge(int edge, int expected) {
        int sample = SmallThumbnailRenderer.subsampling(edge, edge);
        assertEquals(expected, sample);
        long intermediate = ((long) edge + sample - 1) / sample;
        assertTrue(intermediate <= 640);
        assertTrue(intermediate >= Math.min(edge, 320));
        assertThrows(IllegalArgumentException.class, () -> SmallThumbnailRenderer.subsampling(0, edge));
    }

    @Test
    void definitionHashComesFromExactEffectiveJson() throws Exception {
        var definition = SmallThumbnailDefinition.definition();
        assertEquals("builtin.imageio.small-thumbnail", definition.generatorId());
        assertEquals("1", definition.generatorVersion());
        assertEquals(1, definition.configurationVersion());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(definition.configurationJson().getBytes(StandardCharsets.UTF_8))), definition.configurationHash());
    }

    @Test
    void rejectsOversizedEncodedScanlinesBeforeImageDecode() throws Exception {
        Path source = write(directory, "large-header.png", oversizedPngHeader());
        Path output = Files.createFile(directory.resolve("output.tmp"));
        var failure = assertThrows(java.io.IOException.class, () -> renderer.render(source, output));
        assertEquals("Encoded image exceeds safe decoder edge limit", failure.getMessage());
        assertEquals(0, Files.size(output));
    }

    @Test
    void realApp1JpegRotatesBeforeDeterminingDisplayedDimensionsAndPixels() throws Exception {
        Path source = write(directory, "orientation.jpg",
                withOrientation(encoded("JPEG", 800, 400, false), 6, ByteOrder.LITTLE_ENDIAN));
        Path output = Files.createFile(directory.resolve("output.tmp"));
        assertEquals(new SmallThumbnailRenderer.Dimensions(160, 320), renderer.render(source, output).orElseThrow());
        var image = ImageIO.read(output.toFile());
        // Raw bottom-left blue becomes displayed top-left after 90 degrees clockwise.
        int color = image.getRGB(10, 10);
        assertTrue((color & 0xff) > ((color >> 16) & 0xff) + 100);
    }
}
