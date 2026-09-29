package io.github.topher6835.mediacompare.preview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Change the version/configuration when any output-affecting rule changes. */
public final class SmallThumbnailDefinition {
    public static final int MAX_EDGE = 320;
    public static final int DECODE_EDGE = 2 * MAX_EDGE;
    // JPEG's format limit also bounds PNG decoder scanline allocations before subsampling.
    public static final int MAX_ENCODED_EDGE = 65_535;
    public static final String EXTENSION = "png";
    public static final String MEDIA_TYPE = "image/png";
    private static final String CONFIGURATION = """
            {"maxDisplayedEdge":320,"upscale":false,"aspectRatio":"preserve","inputs":["JPEG","PNG"],"output":"PNG","jpegExifOrientation":"1..8","exifHeaderByteLimit":1048576,"unusableOrientation":1,"interpolation":"Java2D-bicubic-quality","subsampling":"ceil(maxEncodedEdge/640)","dimensionRounding":"nearest-minimum-one","alpha":"preserve","outputPixels":"RGB-or-ARGB","orientationTransform":"exact-pixel-permutation","pngEncoder":"JDK-ImageIO-default","maxEncodedEdge":65535,"decodePixelLimit":409600}
            """.strip();
    private static final PreviewDefinition DEFINITION = createDefinition();

    private SmallThumbnailDefinition() {
    }

    public static PreviewDefinition definition() {
        return DEFINITION;
    }

    private static PreviewDefinition createDefinition() {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(CONFIGURATION.getBytes(StandardCharsets.UTF_8)));
            return new PreviewDefinition("builtin.imageio.small-thumbnail", "1", 1, hash, CONFIGURATION);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 unavailable", exception);
        }
    }
}
