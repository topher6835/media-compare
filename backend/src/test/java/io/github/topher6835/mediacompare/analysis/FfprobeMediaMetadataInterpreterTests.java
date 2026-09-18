package io.github.topher6835.mediacompare.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

class FfprobeMediaMetadataInterpreterTests {

    private final FfprobeMediaMetadataInterpreter interpreter =
            new FfprobeMediaMetadataInterpreter(
                    new FfprobeOutputParser(JsonMapper.builder().build()));

    @Test
    void definesIndependentFfprobeAnalysisIdentity() throws Exception {
        MediaMetadataAnalysisDefinition definition = FfprobeMediaMetadataDefinition.definition();

        assertEquals("MEDIA_METADATA", MediaMetadataAnalysisDefinition.ANALYSIS_TYPE);
        assertEquals("builtin.ffprobe", definition.analyzerId());
        assertEquals("1", definition.analyzerVersion());
        assertEquals(1, definition.configurationVersion());
        assertEquals("{}", definition.configurationJson());
        assertEquals(sha256("{}"), definition.configurationHash());
    }

    @Test
    void interpretsRepresentativeMovieAndIgnoresUnknownFields() {
        String json = """
                {
                  "format": {
                    "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                    "duration": "12.345678",
                    "size": "123456",
                    "tags": {
                      "major_brand": "isom",
                      "compatible_brands": "isomiso2avc1mp41",
                      "encoder": "fixture"
                    }
                  },
                  "streams": [
                    {
                      "index": 0,
                      "codec_type": "video",
                      "codec_name": "h264",
                      "width": 1920,
                      "height": 1080,
                      "profile": "High",
                      "disposition": {
                        "default": 1,
                        "attached_pic": 0,
                        "timed_thumbnails": 0,
                        "still_image": 0,
                        "comment": 0
                      }
                    },
                    {
                      "index": 1,
                      "codec_type": "audio",
                      "codec_name": "aac",
                      "disposition": {
                        "default": 1,
                        "attached_pic": 0,
                        "timed_thumbnails": 0,
                        "still_image": 0
                      }
                    }
                  ],
                  "programs": []
                }
                """;

        assertEquals(new VideoMediaMetadata(
                List.of("3g2", "3gp", "m4a", "mj2", "mov", "mp4"),
                12_345_678L, "h264", 1920, 1080, 1, "aac", 1), availableVideo(json));
    }

    @ParameterizedTest(name = "rejects malformed probe output: {0}")
    @MethodSource("malformedProbeOutputs")
    void rejectsMalformedProbeOutput(String description, String json) {
        assertFailed(json, FfprobeInterpretationResult.FailureReason.MALFORMED_PROBE_OUTPUT);
    }

    static Stream<Arguments> malformedProbeOutputs() {
        return Stream.of(
                Arguments.of("malformed JSON", "not-json"),
                Arguments.of("null top level", "null"),
                Arguments.of("array top level", "[]"),
                Arguments.of("missing format", "{\"streams\":[]}"),
                Arguments.of("non-object format", "{\"format\":[],\"streams\":[]}"),
                Arguments.of("non-string format name", "{\"format\":{\"format_name\":1},\"streams\":[]}"),
                Arguments.of("numeric duration", "{\"format\":{\"format_name\":\"mp4\","
                        + "\"duration\":1},\"streams\":[]}"),
                Arguments.of("non-object tags", "{\"format\":{\"format_name\":\"mp4\",\"tags\":[]},\"streams\":[]}"),
                Arguments.of("non-array streams", "{\"format\":{\"format_name\":\"mp4\"},\"streams\":{}}"),
                Arguments.of("negative stream index", probe("mp4", null, null,
                        rawStream("-1", "video", "h264", "1", "1", disposition(0, 0, 0, 0)))),
                Arguments.of("fractional stream index", probe("mp4", null, null,
                        rawStream("0.5", "video", "h264", "1", "1", disposition(0, 0, 0, 0)))),
                Arguments.of("overflowing stream index", probe("mp4", null, null,
                        rawStream("2147483648", "video", "h264", "1", "1", disposition(0, 0, 0, 0)))),
                Arguments.of("duplicate stream index", probe("mp4", null, null,
                        stream(0, "video", "h264", 1, 1, 0, 0, 0, 0),
                        stream(0, "audio", "aac", null, null, 0, 0, 0, 0))),
                Arguments.of("wrong codec type", "{\"format\":{\"format_name\":\"mp4\"},"
                        + "\"streams\":[{\"index\":0,\"codec_type\":1,\"codec_name\":\"h264\","
                        + "\"width\":1,\"height\":1,\"disposition\":"
                        + disposition(0, 0, 0, 0) + "}]}"),
                Arguments.of("negative dimension", probe("mp4", null, null,
                        rawStream("0", "video", "h264", "-1", "1", disposition(0, 0, 0, 0)))),
                Arguments.of("fractional dimension", probe("mp4", null, null,
                        rawStream("0", "video", "h264", "1.5", "1", disposition(0, 0, 0, 0)))),
                Arguments.of("overflowing dimension", probe("mp4", null, null,
                        rawStream("0", "video", "h264", "2147483648", "1", disposition(0, 0, 0, 0)))),
                Arguments.of("missing disposition", "{\"format\":{\"format_name\":\"mp4\"},"
                        + "\"streams\":[{\"index\":0,\"codec_type\":\"video\"}]}"),
                Arguments.of("non-binary disposition", probe("mp4", null, null,
                        rawStream("0", "video", "h264", "1", "1", disposition(2, 0, 0, 0)))),
                Arguments.of("duplicate JSON key", "{\"format\":{\"format_name\":\"mp4\","
                        + "\"format_name\":\"mov\"},\"streams\":[]}"));
    }

    @Test
    void acceptsValidJsonFollowedByWhitespace() {
        assertEquals("h264", availableVideo(movie("1",
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0)) + " \n\t").videoCodec());
    }

    @ParameterizedTest(name = "rejects trailing content: {0}")
    @ValueSource(strings = { "{}", "[]", "42", "\"trailing\"", "trailing" })
    void rejectsTrailingJsonValuesAndContent(String trailingContent) {
        assertFailed(movie("1", stream(0, "video", "h264", 16, 9, 0, 0, 0, 0))
                        + trailingContent,
                FfprobeInterpretationResult.FailureReason.MALFORMED_PROBE_OUTPUT);
    }

    @ParameterizedTest(name = "duration {0} is unavailable")
    @MethodSource("unavailableDurations")
    void supportsUnavailableDuration(String description, String durationJson) {
        String json = "{\"format\":{\"format_name\":\"mp4\"" + durationJson + "},\"streams\":["
                + stream(0, "video", "h264", 16, 9, 0, 0, 0, 0) + "]}";

        assertNull(availableVideo(json).durationMicros());
    }

    static Stream<Arguments> unavailableDurations() {
        return Stream.of(
                Arguments.of("absent", ""),
                Arguments.of("null", ",\"duration\":null"),
                Arguments.of("N/A", ",\"duration\":\"N/A\""));
    }

    @ParameterizedTest(name = "rounds {0} seconds to {1} microseconds")
    @MethodSource("roundedDurations")
    void roundsDurationHalfUp(String seconds, long expectedMicros) {
        assertEquals(expectedMicros, availableVideo(movie(seconds,
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0))).durationMicros());
    }

    static Stream<Arguments> roundedDurations() {
        return Stream.of(
                Arguments.of("0", 0L),
                Arguments.of("0.0000004", 0L),
                Arguments.of("0.0000005", 1L),
                Arguments.of("1.2345674", 1_234_567L),
                Arguments.of("1.2345675", 1_234_568L),
                Arguments.of("9223372036854.7758074", Long.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "-0.1", "+1", ".5", "1.", "1e2", "NaN", "Infinity",
            "9223372036854.7758075"
    })
    void rejectsMalformedNegativeNonfiniteOrOverflowingDuration(String duration) {
        assertFailed(movie(duration, stream(0, "video", "h264", 16, 9, 0, 0, 0, 0)),
                FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA);
    }

    @Test
    void defaultVideoWinsOverLowerNondefaultIndex() {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(2, "video", "hevc", 3840, 2160, 1, 0, 0, 0),
                stream(0, "video", "h264", 1920, 1080, 0, 0, 0, 0)));

        assertEquals("hevc", video.videoCodec());
        assertEquals(3840, video.width());
        assertEquals(2, video.videoStreamCount());
    }

    @Test
    void lowestIndexVideoWinsWhenNoneIsDefault() {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(8, "video", "hevc", 3840, 2160, 0, 0, 0, 0),
                stream(3, "video", "h264", 1920, 1080, 0, 0, 0, 0)));

        assertEquals("h264", video.videoCodec());
        assertEquals(1920, video.width());
    }

    @Test
    void lowestIndexDefaultVideoWinsAmongMultipleDefaults() {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(7, "video", "hevc", 3840, 2160, 1, 0, 0, 0),
                stream(4, "video", "h264", 1920, 1080, 1, 0, 0, 0)));

        assertEquals("h264", video.videoCodec());
        assertEquals(1920, video.width());
    }

    @ParameterizedTest(name = "excludes {0} video disposition")
    @MethodSource("excludedVideoDispositions")
    void excludesArtworkThumbnailAndStillImageStreams(
            String description, int attached, int thumbnail, int still) {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(0, "video", "mjpeg", 600, 600, 1, attached, thumbnail, still),
                stream(1, "video", "h264", 1920, 1080, 0, 0, 0, 0)));

        assertEquals("h264", video.videoCodec());
        assertEquals(1, video.videoStreamCount());
    }

    static Stream<Arguments> excludedVideoDispositions() {
        return Stream.of(
                Arguments.of("attached-picture", 1, 0, 0),
                Arguments.of("timed-thumbnail", 0, 1, 0),
                Arguments.of("still-image", 0, 0, 1));
    }

    @Test
    void doesNotFallThroughWhenPrimaryVideoMetadataIsIncomplete() {
        assertFailed(movie("1",
                stream(4, "video", null, 1920, 1080, 1, 0, 0, 0),
                stream(0, "video", "h264", 1280, 720, 0, 0, 0, 0)),
                FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA);
    }

    @ParameterizedTest(name = "rejects primary video with {0}")
    @MethodSource("invalidPrimaryVideoFields")
    void rejectsInvalidRequiredPrimaryVideoMetadata(
            String description, String codec, Integer width, Integer height) {
        assertFailed(movie("1", stream(0, "video", codec, width, height, 0, 0, 0, 0)),
                FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA);
    }

    static Stream<Arguments> invalidPrimaryVideoFields() {
        return Stream.of(
                Arguments.of("missing codec", null, 16, 9),
                Arguments.of("unknown codec", "unknown", 16, 9),
                Arguments.of("missing width", "h264", null, 9),
                Arguments.of("zero width", "h264", 0, 9),
                Arguments.of("missing height", "h264", 16, null),
                Arguments.of("zero height", "h264", 16, 0));
    }

    @Test
    void supportsVideoWithoutAudio() {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0)));

        assertEquals(0, video.audioStreamCount());
        assertNull(video.audioCodec());
    }

    @Test
    void selectsDefaultAudioThenLowestDefaultIndex() {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0),
                stream(1, "audio", "mp3", null, null, 0, 0, 0, 0),
                stream(7, "audio", "opus", null, null, 1, 0, 0, 0),
                stream(4, "audio", "aac", null, null, 1, 0, 0, 0)));

        assertEquals(3, video.audioStreamCount());
        assertEquals("aac", video.audioCodec());
    }

    @Test
    void lowestIndexAudioWinsWhenNoneIsDefault() {
        VideoMediaMetadata video = availableVideo(movie("1",
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0),
                stream(9, "audio", "opus", null, null, 0, 0, 0, 0),
                stream(2, "audio", "aac", null, null, 0, 0, 0, 0)));

        assertEquals(2, video.audioStreamCount());
        assertEquals("aac", video.audioCodec());
    }

    @ParameterizedTest
    @ValueSource(strings = { "null", "\"unknown\"" })
    void permitsAudioStreamsWithUnknownPrimaryCodec(String codecJson) {
        String audio = rawStream("1", "audio", codecJson, "null", "null", disposition(0, 0, 0, 0));
        VideoMediaMetadata video = availableVideo(probe("mp4", "1", null,
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0), audio));

        assertEquals(1, video.audioStreamCount());
        assertNull(video.audioCodec());
    }

    @Test
    void normalizesContainersAndCodecsWithoutInventingAliases() {
        VideoMediaMetadata first = availableVideo(probe(
                " WEBM, MOV, mp4, mov,, ", "1", null,
                stream(0, "video", " H264 ", 1920, 1080, 0, 0, 0, 0),
                stream(1, "audio", " AAC ", null, null, 0, 0, 0, 0)));
        VideoMediaMetadata second = availableVideo(probe(
                "mp4,webm,mov", "1", null,
                stream(0, "video", "h264", 1920, 1080, 0, 0, 0, 0),
                stream(1, "audio", "aac", null, null, 0, 0, 0, 0)));

        assertEquals(List.of("mov", "mp4", "webm"), first.containerFormats());
        assertEquals(first.containerFormats(), second.containerFormats());
        assertEquals("h264", first.videoCodec());
        assertEquals("aac", first.audioCodec());
    }

    @ParameterizedTest(name = "rejects invalid normalized identifier: {0}")
    @MethodSource("invalidIdentifiers")
    void rejectsInvalidIdentifiers(
            String description,
            String format,
            String codec,
            FfprobeInterpretationResult.FailureReason reason) {
        assertFailed(probe(format, "1", null,
                stream(0, "video", codec, 16, 9, 0, 0, 0, 0)),
                reason);
    }

    static Stream<Arguments> invalidIdentifiers() {
        return Stream.of(
                Arguments.of("container punctuation", "mp4/container", "h264",
                        FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA),
                Arguments.of("codec punctuation", "mp4", "h264/profile",
                        FfprobeInterpretationResult.FailureReason.MALFORMED_PROBE_OUTPUT),
                Arguments.of("empty container aliases", ",,", "h264",
                        FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA),
                Arguments.of("excessive container identifier", "a".repeat(101), "h264",
                        FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA),
                Arguments.of("excessive codec identifier", "mp4", "a".repeat(101),
                        FfprobeInterpretationResult.FailureReason.MALFORMED_PROBE_OUTPUT));
    }

    @ParameterizedTest(name = "classifies {0} as image-oriented")
    @ValueSource(strings = {
            "jpeg_pipe", "png_pipe", "jpeg", "png", "gif", "apng",
            "avif", "heif", "heic", "image2", "image2pipe"
    })
    void classifiesImageDemuxersAsUnsupported(String format) {
        assertUnsupported(probe(format, null, null,
                stream(0, "video", "png", 32, 24, 0, 0, 0, 0)));
    }

    @ParameterizedTest(name = "classifies ISO BMFF image brand {0} as image-oriented")
    @ValueSource(strings = { "avif", "avis", "heic", "heix", "mif1", "msf1" })
    void classifiesIsoBmffImageBrandsAsUnsupported(String brand) {
        assertUnsupported(probe("mov,mp4,m4a,3gp,3g2,mj2", null,
                "\"tags\":{\"major_brand\":\"" + brand + "\","
                        + "\"compatible_brands\":\"isommp42\"}",
                stream(0, "video", "av1", 800, 600, 0, 0, 0, 0)));
    }

    @Test
    void imageCompatibleBrandOverridesGenericAndMovieBrands() {
        assertUnsupported(probe("mov,mp4,m4a,3gp,3g2,mj2", null,
                "\"tags\":{\"major_brand\":\"isom\","
                        + "\"compatible_brands\":\"isommp42avif\"}",
                stream(0, "video", "av1", 800, 600, 0, 0, 0, 0)));
    }

    @Test
    void preservesMeaningfulBrandSpacesAndDoesNotRequireMovieBrandAllowlist() {
        VideoMediaMetadata video = availableVideo(probe("mov,mp4,m4a,3gp,3g2,mj2", "1",
                "\"tags\":{\"major_brand\":\"qt  \","
                        + "\"compatible_brands\":\"qt  isom\"}",
                stream(0, "video", "prores", 1920, 1080, 0, 0, 0, 0)));

        assertEquals("prores", video.videoCodec());
    }

    @Test
    void rejectsMalformedIsoBmffBrandEvidence() {
        assertFailed(probe("mov,mp4,m4a,3gp,3g2,mj2", "1",
                "\"tags\":{\"major_brand\":\"isom\",\"compatible_brands\":\"isomavi\"}",
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0)),
                FfprobeInterpretationResult.FailureReason.INVALID_MEDIA_METADATA);
    }

    @ParameterizedTest
    @ValueSource(strings = { "concat", "hls", "applehttp", "dash" })
    void classifiesExternalPresentationsAsUnsupported(String format) {
        assertUnsupported(probe(format, "1", null,
                stream(0, "video", "h264", 16, 9, 0, 0, 0, 0)));
    }

    @Test
    void classifiesAudioOnlyContentAsUnsupported() {
        assertUnsupported(probe("matroska,webm", "2", null,
                stream(0, "audio", "opus", null, null, 1, 0, 0, 0)));
    }

    @Test
    void classifiesAudioWithOnlyAttachedArtworkAsUnsupported() {
        assertUnsupported(probe("mov,mp4,m4a,3gp,3g2,mj2", "2", null,
                stream(0, "audio", "aac", null, null, 1, 0, 0, 0),
                stream(1, "video", "mjpeg", 600, 600, 0, 1, 0, 0)));
    }

    @Test
    void classifiesContentWithOnlyExcludedVideoStreamsAsUnsupported() {
        assertUnsupported(probe("matroska,webm", "2", null,
                stream(0, "video", "mjpeg", 600, 600, 0, 1, 0, 0),
                stream(1, "video", "png", 320, 180, 0, 0, 1, 0),
                stream(2, "video", "png", 160, 90, 0, 0, 0, 1)));
    }

    @Test
    void classifiesRecognizedContentWithoutStreamsAsUnsupported() {
        assertUnsupported(probe("data", null, null));
    }

    @Test
    void acceptsSingleFrameStyleMovieWithoutRequiringMotionEvidence() {
        VideoMediaMetadata video = availableVideo(probe("matroska,webm", null, null,
                stream(0, "video", "vp9", 640, 480, 0, 0, 0, 0)));

        assertEquals("vp9", video.videoCodec());
    }

    @ParameterizedTest(name = "accepts {0} codec in a genuine movie structure")
    @ValueSource(strings = { "mjpeg", "png" })
    void doesNotClassifyCodecAloneAsImageOrVideo(String codec) {
        VideoMediaMetadata video = availableVideo(probe("matroska,webm", "1", null,
                stream(0, "video", codec, 640, 480, 0, 0, 0, 0)));

        assertEquals(codec, video.videoCodec());
    }

    private VideoMediaMetadata availableVideo(String json) {
        FfprobeInterpretationResult.Completed completed = assertInstanceOf(
                FfprobeInterpretationResult.Completed.class, interpreter.interpret(json));
        AvailableMediaMetadata available = assertInstanceOf(
                AvailableMediaMetadata.class, completed.result());
        assertEquals(MediaKind.VIDEO, available.mediaKind());
        return available.video();
    }

    private void assertUnsupported(String json) {
        FfprobeInterpretationResult.Completed completed = assertInstanceOf(
                FfprobeInterpretationResult.Completed.class, interpreter.interpret(json));
        assertInstanceOf(UnsupportedMediaMetadata.class, completed.result());
    }

    private void assertFailed(String json, FfprobeInterpretationResult.FailureReason reason) {
        FfprobeInterpretationResult.Failed failed = assertInstanceOf(
                FfprobeInterpretationResult.Failed.class, interpreter.interpret(json));
        assertEquals(reason, failed.reason());
    }

    private static String movie(String duration, String... streams) {
        return probe("mov,mp4,m4a,3gp,3g2,mj2", duration, null, streams);
    }

    private static String probe(String format, String duration, String tagsField, String... streams) {
        String durationField = duration == null ? "" : ",\"duration\":\"" + duration + "\"";
        String tags = tagsField == null ? "" : "," + tagsField;
        return "{\"format\":{\"format_name\":\"" + format + "\"" + durationField + tags
                + "},\"streams\":[" + String.join(",", streams) + "]}";
    }

    private static String stream(
            int index,
            String codecType,
            String codecName,
            Integer width,
            Integer height,
            int defaultStream,
            int attached,
            int thumbnail,
            int still) {
        return rawStream(
                Integer.toString(index),
                "\"" + codecType + "\"",
                codecName == null ? "null" : "\"" + codecName + "\"",
                width == null ? "null" : width.toString(),
                height == null ? "null" : height.toString(),
                disposition(defaultStream, attached, thumbnail, still));
    }

    private static String rawStream(
            String index,
            String codecType,
            String codecName,
            String width,
            String height,
            String disposition) {
        String type = codecType.startsWith("\"") ? codecType : "\"" + codecType + "\"";
        String codec = codecName == null || codecName.equals("null") || codecName.startsWith("\"")
                ? codecName : "\"" + codecName + "\"";
        return "{\"index\":" + index + ",\"codec_type\":" + type + ",\"codec_name\":" + codec
                + ",\"width\":" + width + ",\"height\":" + height
                + ",\"disposition\":" + disposition + "}";
    }

    private static String disposition(int defaultStream, int attached, int thumbnail, int still) {
        return "{\"default\":" + defaultStream + ",\"attached_pic\":" + attached
                + ",\"timed_thumbnails\":" + thumbnail + ",\"still_image\":" + still + "}";
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
