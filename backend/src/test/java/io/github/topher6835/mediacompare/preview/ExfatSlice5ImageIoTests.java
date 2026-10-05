package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.preview.ThumbnailImageFixtures.*;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.*;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.contentread.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExfatSlice5ImageIoTests {
    @TempDir Path directory;
    private final ImageIoImageMetadataExtractor extractor = new ImageIoImageMetadataExtractor() {
        @Override public MediaMetadataResult extract(Path ignored) { fail("Original pathname metadata overload used"); return null; }
    };
    private final SmallThumbnailRenderer renderer = new SmallThumbnailRenderer() {
        @Override public java.util.Optional<Dimensions> render(Path ignored, Path output) { fail("Original pathname renderer overload used"); return null; }
    };

    @ParameterizedTest @ValueSource(strings = {"JPEG", "PNG"})
    void suppliedInputNeverOffersProtectedBytesToCompetingProvider(String format) throws Exception {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        var hostile = new HostileSpi(); registry.registerServiceProvider(hostile);
        var providers = registry.getServiceProviders(ImageReaderSpi.class, true);
        while (providers.hasNext()) { var provider = providers.next(); if (provider != hostile) registry.setOrdering(ImageReaderSpi.class, hostile, provider); }
        byte[] bytes = encoded(format, 100, 60, false);
        try (var channel = Files.newByteChannel(write(directory, "source", bytes), StandardOpenOption.READ)) {
            try (var input = new BorrowedImageInput(channel)) {
                var result = (AvailableMediaMetadata) extractor.extract(input);
                assertEquals(100, result.image().width()); assertEquals(format.toLowerCase(Locale.ROOT), result.image().format());
            }
            assertTrue(channel.isOpen()); channel.position(0);
            Path output = Files.createTempFile(directory, "output", ".png");
            try (var input = new BorrowedImageInput(channel)) { assertEquals(100, renderer.render(input, output).orElseThrow().width()); }
            assertTrue(channel.isOpen()); channel.position(0);
            try (var input = new BorrowedImageInput(channel)) {
                byte[] reread = new byte[bytes.length]; input.readFully(reread); assertArrayEquals(bytes, reread);
            }
        } finally { registry.deregisterServiceProvider(hostile); }
    }

    @Test void absentApprovedPngProviderIsInfrastructureFailureWithoutFallback() throws Exception {
        var registry = IIORegistry.getDefaultInstance();
        var providers = registry.getServiceProviders(ImageReaderSpi.class, true);
        ImageReaderSpi png = null;
        while (providers.hasNext()) { var next = providers.next(); if (next.getClass().getName().equals("com.sun.imageio.plugins.png.PNGImageReaderSpi")) png = next; }
        assertNotNull(png); registry.deregisterServiceProvider(png);
        try (var channel = Files.newByteChannel(write(directory, "png", encoded("PNG", 2, 2, false)))) {
            try (var input = new BorrowedImageInput(channel)) { assertThrows(IllegalStateException.class, () -> extractor.extract(input)); }
        } finally { registry.registerServiceProvider(png); }
    }

    @Test void pngFlushDoesNotPreventUnderlyingChannelSecondHash() throws Exception {
        byte[] bytes = encoded("PNG", 20, 30, true);
        try (var channel = Files.newByteChannel(write(directory, "png", bytes))) {
            try (var input = new BorrowedImageInput(channel)) {
                var reader = JdkOriginalImageReaders.select(input).orElseThrow();
                try { reader.setInput(input, false, true); assertEquals(20, reader.read(0).getWidth()); }
                finally { reader.dispose(); }
                input.flushBefore(input.getStreamPosition());
                assertThrows(IndexOutOfBoundsException.class, () -> input.seek(0));
            }
            assertTrue(channel.isOpen()); channel.position(0);
            byte[] reread = new byte[bytes.length]; channel.read(java.nio.ByteBuffer.wrap(reread));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), MessageDigest.getInstance("SHA-256").digest(reread));
        }
    }

    @Test void suppliedJpegExifRotatesPreviewButKeepsEncodedMetadataDimensions() throws Exception {
        byte[] bytes = withOrientation(encoded("JPEG", 3, 2, false), 6, ByteOrder.LITTLE_ENDIAN);
        try (var channel = Files.newByteChannel(write(directory, "jpeg", bytes))) {
            try (var input = new BorrowedImageInput(channel)) {
                var metadata = (AvailableMediaMetadata) extractor.extract(input);
                assertEquals(3, metadata.image().width()); assertEquals(2, metadata.image().height());
            }
            channel.position(0);
            try (var input = new BorrowedImageInput(channel)) {
                var dimensions = renderer.render(input, Files.createTempFile(directory, "preview", ".png")).orElseThrow();
                assertEquals(2, dimensions.width()); assertEquals(3, dimensions.height());
            }
        }
    }

    @Test void suppliedDecoderRejectsUnsafeDimensionsAndMalformedRecognizedImage() throws Exception {
        try (var channel = Files.newByteChannel(write(directory, "oversized", oversizedPngHeader())); var input = new BorrowedImageInput(channel)) {
            assertThrows(IOException.class, () -> renderer.render(input, Files.createTempFile(directory, "output", ".png")));
        }
        try (var channel = Files.newByteChannel(write(directory, "truncated", new byte[] {(byte)255, (byte)216, 0})); var input = new BorrowedImageInput(channel)) {
            assertThrows(ImageMetadataExtractionException.class, () -> extractor.extract(input));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"GIF", "BMP", "TIFF"})
    void formatsRemainNativeOnly(String format) throws Exception {
        byte[] bytes = encoded(format, 4, 5, false);
        try (var channel = Files.newByteChannel(write(directory, format, bytes)); var input = new BorrowedImageInput(channel)) {
            assertInstanceOf(UnsupportedMediaMetadata.class, extractor.extract(input));
        }
        assertInstanceOf(AvailableMediaMetadata.class, new ImageIoImageMetadataExtractor().extract(directory.resolve(format)));
    }

    static final class HostileSpi extends ImageReaderSpi {
        @Override public boolean canDecodeInput(Object input) { fail("Hostile SPI received protected input"); return false; }
        @Override public ImageReader createReaderInstance(Object extension) { fail("Hostile reader created"); return null; }
        @Override public String getDescription(Locale locale) { return "Test forbidden provider"; }
    }
}
