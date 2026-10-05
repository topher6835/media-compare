package io.github.topher6835.mediacompare.contentread;

import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/** Filter SPI identity BEFORE handing any protected bytes to canDecodeInput. */
public final class JdkOriginalImageReaders {
    private JdkOriginalImageReaders() { }
    public static Optional<ImageReader> select(ImageInputStream input) throws IOException {
        long origin = input.getStreamPosition();
        byte[] header = new byte[8];
        int count = input.read(header);
        input.seek(origin);
        boolean jpeg = count >= 2 && (header[0] & 255) == 255 && (header[1] & 255) == 216;
        boolean png = count >= 4 && (header[0] & 255) == 137 && header[1] == 80 && header[2] == 78 && header[3] == 71;
        if (!jpeg && !png) return Optional.empty();
        boolean found = false;
        var providers = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, true);
        while (providers.hasNext()) {
            ImageReaderSpi provider = providers.next();
            String name = provider.getClass().getName();
            String readerName = switch (name) {
                case "com.sun.imageio.plugins.jpeg.JPEGImageReaderSpi" -> "com.sun.imageio.plugins.jpeg.JPEGImageReader";
                case "com.sun.imageio.plugins.png.PNGImageReaderSpi" -> "com.sun.imageio.plugins.png.PNGImageReader";
                default -> null;
            };
            if (readerName == null || provider.getClass().getModule() != ImageReader.class.getModule()
                    || jpeg != name.contains(".jpeg.")) continue;
            found = true;
            long position = input.getStreamPosition();
            boolean supported;
            try { supported = provider.canDecodeInput(input); }
            finally { input.seek(position); }
            if (!supported) continue;
            ImageReader reader = provider.createReaderInstance();
            if (!reader.getClass().getName().equals(readerName)
                    || reader.getClass().getModule() != ImageReader.class.getModule()
                    || reader.getOriginatingProvider() != provider) {
                reader.dispose();
                throw new IOException("Unexpected protected-original reader identity");
            }
            return Optional.of(reader);
        }
        if (!found) throw new DecoderUnavailableException();
        throw new IOException("Recognized image is malformed");
    }
    public static final class DecoderUnavailableException extends IOException {
        DecoderUnavailableException() { super("Approved JDK image decoder unavailable"); }
    }
}
