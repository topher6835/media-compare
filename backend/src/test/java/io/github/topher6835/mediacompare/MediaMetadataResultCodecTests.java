package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;

import io.github.topher6835.mediacompare.analysis.AvailableMediaMetadata;
import io.github.topher6835.mediacompare.analysis.ImageMediaMetadata;
import io.github.topher6835.mediacompare.analysis.InvalidMediaMetadataResultException;
import io.github.topher6835.mediacompare.analysis.MediaKind;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResult;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.analysis.UnsupportedMediaMetadata;
import io.github.topher6835.mediacompare.analysis.VideoMediaMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

class MediaMetadataResultCodecTests {

    private final MediaMetadataResultCodec codec = new MediaMetadataResultCodec(JsonMapper.builder().build());

    @Test
    void roundTripsAvailableImage() {
        MediaMetadataResult result = new AvailableMediaMetadata(
                1, MediaKind.IMAGE, new ImageMediaMetadata("jpeg", 4032, 3024), null);

        String stored = codec.write(result);

        assertEquals("{\"version\":1,\"outcome\":\"AVAILABLE\",\"mediaKind\":\"IMAGE\","
                + "\"image\":{\"format\":\"jpeg\",\"width\":4032,\"height\":3024}}", stored);
        assertEquals(result, codec.read(stored));
    }

    @Test
    void roundTripsAvailableVideoWithAudioStream() {
        MediaMetadataResult result = new AvailableMediaMetadata(
                1, MediaKind.VIDEO, null, new VideoMediaMetadata(
                        List.of("mov", "mp4"), 12_345_678L, "h264", 1920, 1080, 1, "aac", 1));

        String stored = codec.write(result);

        assertEquals("{\"version\":1,\"outcome\":\"AVAILABLE\",\"mediaKind\":\"VIDEO\","
                + "\"video\":{\"containerFormats\":[\"mov\",\"mp4\"],\"durationMicros\":12345678,"
                + "\"videoCodec\":\"h264\",\"width\":1920,\"height\":1080,\"videoStreamCount\":1,"
                + "\"audioCodec\":\"aac\",\"audioStreamCount\":1}}", stored);
        assertEquals(result, codec.read(stored));
    }

    @Test
    void roundTripsAvailableVideoWithoutAudioStream() {
        MediaMetadataResult result = new AvailableMediaMetadata(
                1, MediaKind.VIDEO, null, new VideoMediaMetadata(
                        List.of("matroska"), null, "hevc", 3840, 2160, 1, null, 0));

        String stored = codec.write(result);

        assertEquals(result, codec.read(stored));
    }

    @Test
    void roundTripsUnsupported() {
        MediaMetadataResult result = new UnsupportedMediaMetadata(1);

        assertEquals("{\"version\":1,\"outcome\":\"UNSUPPORTED\"}", codec.write(result));
        assertEquals(result, codec.read(codec.write(result)));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidStoredResults")
    void rejectsInvalidStoredResults(String description, String stored) {
        assertThrows(InvalidMediaMetadataResultException.class, () -> codec.read(stored));
    }

    static Stream<Object[]> invalidStoredResults() {
        return Stream.of(
                new Object[] { "malformed JSON", "not-json" },
                new Object[] { "unsupported version", "{\"version\":2,\"outcome\":\"UNSUPPORTED\"}" },
                new Object[] { "missing outcome", "{\"version\":1}" },
                new Object[] { "invalid outcome", "{\"version\":1,\"outcome\":\"FAILED\"}" },
                new Object[] { "invalid media kind", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"AUDIO\",\"image\":{\"format\":\"jpeg\",\"width\":1,\"height\":1}}" },
                new Object[] { "image kind with video payload", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"IMAGE\",\"video\":{\"containerFormats\":[\"mp4\"],"
                        + "\"durationMicros\":1,\"videoCodec\":\"h264\",\"width\":1,\"height\":1,"
                        + "\"videoStreamCount\":1,\"audioCodec\":null,\"audioStreamCount\":0}}" },
                new Object[] { "video kind with image payload", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"VIDEO\",\"image\":{\"format\":\"jpeg\",\"width\":1,\"height\":1}}" },
                new Object[] { "zero image width", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"IMAGE\",\"image\":{\"format\":\"jpeg\",\"width\":0,\"height\":1}}" },
                new Object[] { "non-normalized image format", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"IMAGE\",\"image\":{\"format\":\"JPEG\",\"width\":1,\"height\":1}}" },
                new Object[] { "negative video duration", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"VIDEO\",\"video\":{\"containerFormats\":[\"mp4\"],"
                        + "\"durationMicros\":-1,\"videoCodec\":\"h264\",\"width\":1,\"height\":1,"
                        + "\"videoStreamCount\":1,\"audioCodec\":null,\"audioStreamCount\":0}}" },
                new Object[] { "negative stream count", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"VIDEO\",\"video\":{\"containerFormats\":[\"mp4\"],"
                        + "\"durationMicros\":null,\"videoCodec\":\"h264\",\"width\":1,\"height\":1,"
                        + "\"videoStreamCount\":-1,\"audioCodec\":null,\"audioStreamCount\":0}}" },
                new Object[] { "zero video stream count", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"VIDEO\",\"video\":{\"containerFormats\":[\"mp4\"],"
                        + "\"durationMicros\":null,\"videoCodec\":\"h264\",\"width\":1,\"height\":1,"
                        + "\"videoStreamCount\":0,\"audioCodec\":null,\"audioStreamCount\":0}}" },
                new Object[] { "audio codec without audio stream", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"VIDEO\",\"video\":{\"containerFormats\":[\"mp4\"],"
                        + "\"durationMicros\":null,\"videoCodec\":\"h264\",\"width\":1920,\"height\":1080,"
                        + "\"videoStreamCount\":1,\"audioCodec\":\"aac\",\"audioStreamCount\":0}}" },
                new Object[] { "fractional dimension", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"IMAGE\",\"image\":{\"format\":\"jpeg\",\"width\":1.5,\"height\":1}}" },
                new Object[] { "overflowing dimension", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"IMAGE\",\"image\":{\"format\":\"jpeg\",\"width\":2147483648,\"height\":1}}" },
                new Object[] { "fractional duration", "{\"version\":1,\"outcome\":\"AVAILABLE\","
                        + "\"mediaKind\":\"VIDEO\",\"video\":{\"containerFormats\":[\"mp4\"],"
                        + "\"durationMicros\":1.5,\"videoCodec\":\"h264\",\"width\":1,\"height\":1,"
                        + "\"videoStreamCount\":1,\"audioCodec\":null,\"audioStreamCount\":0}}" },
                new Object[] { "unexpected field", "{\"version\":1,\"outcome\":\"UNSUPPORTED\",\"unexpected\":true}" });
    }
}
