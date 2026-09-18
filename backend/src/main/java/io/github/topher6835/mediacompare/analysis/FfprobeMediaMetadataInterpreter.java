package io.github.topher6835.mediacompare.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class FfprobeMediaMetadataInterpreter {

    private static final int MAX_IDENTIFIER_LENGTH = 100;
    private static final BigDecimal MICROS_PER_SECOND = BigDecimal.valueOf(1_000_000);

    private static final Set<String> IMAGE_FORMATS = Set.of(
            "apng", "avif", "avis", "bmp", "gif", "heic", "heif", "ico",
            "image2", "image2pipe", "jpeg", "jpegxl", "jpegxl_anim", "jpg",
            "png", "tiff", "webp");
    private static final Set<String> EXTERNAL_PRESENTATION_FORMATS = Set.of(
            "applehttp", "concat", "dash", "hls");
    private static final Set<String> ISO_BMFF_FORMATS = Set.of(
            "mov", "mp4", "m4a", "3gp", "3g2", "mj2");
    private static final Set<String> ISO_BMFF_IMAGE_BRANDS = Set.of(
            "avif", "avis", "heic", "heix", "heim", "heis", "hevc", "hevx",
            "hevm", "hevs", "mif1", "mif2", "msf1", "miaf");

    private final FfprobeOutputParser outputParser;

    FfprobeMediaMetadataInterpreter(FfprobeOutputParser outputParser) {
        this.outputParser = outputParser;
    }

    public FfprobeInterpretationResult interpret(String probeJson) {
        FfprobeOutput output;
        try {
            output = outputParser.parse(probeJson);
        } catch (FfprobeOutputParser.InvalidOutputException exception) {
            return new FfprobeInterpretationResult.Failed(
                    FfprobeInterpretationResult.FailureReason.MALFORMED_PROBE_OUTPUT);
        }

        try {
            return interpret(output);
        } catch (InvalidMediaMetadataException | IllegalArgumentException | ArithmeticException exception) {
            return new FfprobeInterpretationResult.Failed(
                    FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA);
        }
    }

    private static FfprobeInterpretationResult interpret(FfprobeOutput output) {
        List<String> containerFormats = normalizeContainerFormats(output.format().formatName());
        Long durationMicros = durationMicros(output.format().duration());

        if (isExternalPresentation(containerFormats)
                || isImageOriented(containerFormats, output.format())) {
            return completedUnsupported();
        }

        List<FfprobeStream> videos = output.streams().stream()
                .filter(stream -> "video".equals(stream.codecType()))
                .filter(FfprobeMediaMetadataInterpreter::isEligibleVideo)
                .sorted(Comparator.comparingInt(FfprobeStream::index))
                .toList();
        if (videos.isEmpty()) {
            return completedUnsupported();
        }

        FfprobeStream primaryVideo = primary(videos);
        String videoCodec = requiredCodec(primaryVideo.codecName(), "Primary video codec");
        int width = requiredPositiveDimension(primaryVideo.width(), "Primary video width");
        int height = requiredPositiveDimension(primaryVideo.height(), "Primary video height");

        List<FfprobeStream> audios = output.streams().stream()
                .filter(stream -> "audio".equals(stream.codecType()))
                .sorted(Comparator.comparingInt(FfprobeStream::index))
                .toList();
        String audioCodec = audios.isEmpty() ? null : optionalCodec(primary(audios).codecName());

        VideoMediaMetadata video = new VideoMediaMetadata(
                containerFormats,
                durationMicros,
                videoCodec,
                width,
                height,
                videos.size(),
                audioCodec,
                audios.size());
        return new FfprobeInterpretationResult.Completed(new AvailableMediaMetadata(
                MediaMetadataResult.CURRENT_VERSION, MediaKind.VIDEO, null, video));
    }

    private static List<String> normalizeContainerFormats(String formatName) {
        var formats = new LinkedHashSet<String>();
        for (String alias : formatName.split(",", -1)) {
            String value = alias.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                formats.add(normalizedIdentifier(value, "Container format"));
            }
        }
        if (formats.isEmpty()) {
            throw new InvalidMediaMetadataException("At least one container format is required");
        }
        return formats.stream().sorted().toList();
    }

    private static Long durationMicros(String duration) {
        if (duration == null || "N/A".equals(duration)) {
            return null;
        }
        if (!duration.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw new InvalidMediaMetadataException("Duration must be a nonnegative decimal or N/A");
        }
        try {
            return new BigDecimal(duration)
                    .multiply(MICROS_PER_SECOND)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new InvalidMediaMetadataException("Duration is outside the supported range", exception);
        }
    }

    private static boolean isImageOriented(List<String> formats, FfprobeFormat format) {
        if (formats.stream().anyMatch(FfprobeMediaMetadataInterpreter::isImageFormat)) {
            return true;
        }
        if (formats.stream().noneMatch(ISO_BMFF_FORMATS::contains)) {
            return false;
        }
        return isoBmffBrands(format).stream().anyMatch(ISO_BMFF_IMAGE_BRANDS::contains);
    }

    private static boolean isImageFormat(String format) {
        return IMAGE_FORMATS.contains(format) || format.endsWith("_pipe");
    }

    private static boolean isExternalPresentation(List<String> formats) {
        return formats.stream().anyMatch(EXTERNAL_PRESENTATION_FORMATS::contains);
    }

    private static Set<String> isoBmffBrands(FfprobeFormat format) {
        var brands = new LinkedHashSet<String>();
        if (format.majorBrand() != null) {
            requireFourCharacterBrand(format.majorBrand(), "major_brand");
            brands.add(format.majorBrand());
        }
        if (format.compatibleBrands() != null) {
            String compatible = format.compatibleBrands();
            if (compatible.length() % 4 != 0) {
                throw new InvalidMediaMetadataException(
                        "compatible_brands must contain four-character brand codes");
            }
            for (int offset = 0; offset < compatible.length(); offset += 4) {
                brands.add(compatible.substring(offset, offset + 4));
            }
        }
        return Set.copyOf(brands);
    }

    private static void requireFourCharacterBrand(String brand, String fieldName) {
        if (brand.length() != 4) {
            throw new InvalidMediaMetadataException(fieldName + " must be a four-character brand code");
        }
    }

    private static boolean isEligibleVideo(FfprobeStream stream) {
        return !stream.disposition().attachedPicture()
                && !stream.disposition().timedThumbnail()
                && !stream.disposition().stillImage();
    }

    private static FfprobeStream primary(List<FfprobeStream> streams) {
        return streams.stream()
                .filter(stream -> stream.disposition().defaultStream())
                .findFirst()
                .orElse(streams.getFirst());
    }

    private static String requiredCodec(String codecName, String description) {
        String codec = optionalCodec(codecName);
        if (codec == null) {
            throw new InvalidMediaMetadataException(description + " is required");
        }
        return codec;
    }

    private static String optionalCodec(String codecName) {
        return codecName == null || "unknown".equals(codecName) ? null : codecName;
    }

    private static int requiredPositiveDimension(Integer value, String description) {
        if (value == null || value <= 0) {
            throw new InvalidMediaMetadataException(description + " must be positive");
        }
        return value;
    }

    private static String normalizedIdentifier(String value, String description) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(description + " is too long");
        }
        return ImageMediaMetadata.requireNormalizedName(normalized, description);
    }

    private static FfprobeInterpretationResult completedUnsupported() {
        return new FfprobeInterpretationResult.Completed(
                new UnsupportedMediaMetadata(MediaMetadataResult.CURRENT_VERSION));
    }

    private static final class InvalidMediaMetadataException extends RuntimeException {
        private InvalidMediaMetadataException(String message) {
            super(message);
        }

        private InvalidMediaMetadataException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
