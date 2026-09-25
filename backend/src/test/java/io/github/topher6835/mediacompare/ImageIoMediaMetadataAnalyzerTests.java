package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.AvailableMediaMetadata;
import io.github.topher6835.mediacompare.analysis.ImageIoMediaMetadataAnalyzer;
import io.github.topher6835.mediacompare.analysis.ImageIoMediaMetadataDefinition;
import io.github.topher6835.mediacompare.analysis.ImageIoImageMetadataExtractor;
import io.github.topher6835.mediacompare.analysis.ImageMediaMetadata;
import io.github.topher6835.mediacompare.analysis.ImageMetadataExtractor;
import io.github.topher6835.mediacompare.analysis.ImageMetadataExtractionException;
import io.github.topher6835.mediacompare.analysis.MediaKind;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCache;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataContentCandidate;
import io.github.topher6835.mediacompare.analysis.MediaMetadataFileEvidenceValidator;
import io.github.topher6835.mediacompare.analysis.MediaMetadataPublisher;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResult;
import io.github.topher6835.mediacompare.analysis.UnsupportedMediaMetadata;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class ImageIoMediaMetadataAnalyzerTests {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private MediaMetadataCandidateRepository candidateRepository;

    @Autowired
    private MediaMetadataCache metadataCache;

    @Autowired
    private MediaMetadataFileEvidenceValidator evidenceValidator;

    @Autowired
    private MediaMetadataPublisher publisher;

    @Autowired
    private ImageIoMediaMetadataAnalyzer analyzer;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
        jdbcTemplate.update("DELETE FROM source_membership");
        jdbcTemplate.update("DELETE FROM file_entry");
        jdbcTemplate.update("DELETE FROM scan_run_source");
        jdbcTemplate.update("DELETE FROM working_set_content");
        jdbcTemplate.update("DELETE FROM analysis_record");
        jdbcTemplate.update("DELETE FROM job");
        jdbcTemplate.update("DELETE FROM scan_run");
        jdbcTemplate.update("DELETE FROM working_set");
        jdbcTemplate.update("DELETE FROM content_record");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM location_context");
    }

    @ParameterizedTest(name = "extracts {0} as canonical {1}")
    @MethodSource("standardFormats")
    void extractsSupportedImageMetadataFromBytes(
            String imageIoFormat, String canonicalFormat, String relativePath) throws Exception {
        Fixture fixture = createImageFixture("supported-" + imageIoFormat,
                imageIoFormat, relativePath, 7, 5);

        MediaMetadataResult result = analyze(fixture);

        assertEquals(available(canonicalFormat, 7, 5), result);
        assertCompletedArtifact(fixture.content().id(), result);
    }

    static Stream<Arguments> standardFormats() {
        return Stream.of(
                Arguments.of("jpeg", "jpeg", "misleading.bin"),
                Arguments.of("png", "png", "image.data"),
                Arguments.of("gif", "gif", "image.unknown"),
                Arguments.of("bmp", "bmp", "image.txt"));
    }

    @Test
    void extractsTiffWhenTheRuntimeProvidesBothReaderAndWriter() throws Exception {
        Assumptions.assumeTrue(ImageIO.getImageWritersByFormatName("tiff").hasNext());
        Assumptions.assumeTrue(ImageIO.getImageReadersByFormatName("tiff").hasNext());
        Fixture fixture = createImageFixture("tiff", "tiff", "image.bin", 6, 4);

        MediaMetadataResult result = analyze(fixture);

        assertEquals(available("tiff", 6, 4), result);
        assertCompletedArtifact(fixture.content().id(), result);
    }

    @Test
    void textWithJpegExtensionPublishesUnsupportedRatherThanAvailable() throws Exception {
        Fixture fixture = createTextFixture("text-jpg", "photo.jpg", "ordinary text");

        MediaMetadataResult result = analyze(fixture);

        assertEquals(unsupported(), result);
        assertCompletedArtifact(fixture.content().id(), result);
    }

    @Test
    void ordinaryBinaryDataPublishesUnsupported() throws Exception {
        Fixture fixture = createTextFixture("text-bin", "photo.bin", "not an image");

        MediaMetadataResult result = analyze(fixture);

        assertEquals(unsupported(), result);
        assertCompletedArtifact(fixture.content().id(), result);
    }

    @Test
    void corruptRecognizedJpegIsAnExtractionErrorRatherThanUnsupported() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("corrupt-jpeg"));
        Files.write(root.resolve("corrupt.bin"), new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9 });
        Fixture fixture = catalogFixture(root, "corrupt-jpeg", List.of("corrupt.bin"));

        assertThrows(ImageMetadataExtractionException.class,
                () -> analyzer.analyze(candidate(fixture)));
        assertEquals(0, metadataArtifactCount(fixture.content().id()));
    }

    @Test
    void staleExtractionFailureFallsBackToSecondCurrentImage() throws Exception {
        Fixture fixture = createCopiedImageFixture(
                "stale-extraction-failure", "png", "first.bin", "second.bin", 9, 7);
        ImageMetadataExtractor extractor = file -> {
            if (file.equals(fixture.paths().getFirst())) {
                try {
                    Files.writeString(file, "changed during extraction");
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
                throw new ImageMetadataExtractionException(
                        "simulated extractor failure", new IOException("simulated"));
            }
            return new ImageIoImageMetadataExtractor().extract(file);
        };
        ImageIoMediaMetadataAnalyzer analyzerWithFailingFirstExtractor = new ImageIoMediaMetadataAnalyzer(
                candidateRepository, metadataCache, evidenceValidator, extractor, publisher);

        MediaMetadataResult result = analyzerWithFailingFirstExtractor.analyze(candidate(fixture)).orElseThrow();

        assertEquals(available("png", 9, 7), result);
        assertCompletedArtifact(fixture.content().id(), result);
        assertEquals(1, metadataArtifactCount(fixture.content().id()));
    }

    @Test
    void completedImageResultIsReusedAfterTheOccurrenceBecomesMissing() throws Exception {
        Fixture fixture = createImageFixture("cache", "png", "photo.bin", 8, 6);
        MediaMetadataResult first = analyze(fixture);
        jdbcTemplate.update("""
                UPDATE source_membership SET presence_status = 'MISSING'
                WHERE file_entry_id IN (SELECT id FROM file_entry WHERE current_content_id = ?)
                """,
                fixture.content().id());
        Files.delete(fixture.paths().getFirst());

        Optional<MediaMetadataResult> cached = analyzer.analyze(candidate(fixture));

        assertEquals(first, cached.orElseThrow());
        assertEquals(1, metadataArtifactCount(fixture.content().id()));
        assertEquals(first, metadataCache.findReusableResult(
                fixture.content().id(), ImageIoMediaMetadataDefinition.definition()).orElseThrow());
    }

    @Test
    void staleFirstOccurrenceFallsBackToSecondCurrentImage() throws Exception {
        Fixture fixture = createCopiedImageFixture("fallback", "png", "first.bin", "second.bin", 9, 7);
        Files.delete(fixture.paths().getFirst());

        Optional<MediaMetadataResult> result = analyzer.analyze(candidate(fixture));

        assertEquals(available("png", 9, 7), result.orElseThrow());
        assertCompletedArtifact(fixture.content().id(), result.orElseThrow());
    }

    @Test
    void allStaleOccurrencesLeaveNoArtifact() throws Exception {
        Fixture fixture = createCopiedImageFixture("all-stale", "png", "first.bin", "second.bin", 9, 7);
        Files.delete(fixture.paths().getFirst());
        Files.delete(fixture.paths().getLast());

        assertTrue(analyzer.analyze(candidate(fixture)).isEmpty());
        assertEquals(0, metadataArtifactCount(fixture.content().id()));
    }

    @Test
    void imageAnalyzerDoesNotStartATransaction() throws Exception {
        assertNull(ImageIoMediaMetadataAnalyzer.class.getMethod(
                "analyze", MediaMetadataContentCandidate.class).getAnnotation(Transactional.class));
        assertNotNull(ImageIoMediaMetadataDefinition.definition());
        assertEquals("builtin.imageio", ImageIoMediaMetadataDefinition.ANALYZER_ID);
        assertEquals("{}", ImageIoMediaMetadataDefinition.CONFIGURATION_JSON);
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                ImageIoMediaMetadataDefinition.CONFIGURATION_HASH);
    }

    private MediaMetadataResult analyze(Fixture fixture) {
        return analyzer.analyze(candidate(fixture)).orElseThrow();
    }

    private MediaMetadataContentCandidate candidate(Fixture fixture) {
        return new MediaMetadataContentCandidate(fixture.content().id(), fixture.content().sizeBytes());
    }

    private Fixture createImageFixture(
            String directoryName, String imageIoFormat, String relativePath, int width, int height)
            throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        Path image = root.resolve(relativePath);
        writeImage(image, imageIoFormat, width, height);
        return catalogFixture(root, directoryName, List.of(relativePath));
    }

    private Fixture createCopiedImageFixture(
            String directoryName, String imageIoFormat, String firstPath, String secondPath,
            int width, int height) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        Path first = root.resolve(firstPath);
        Path second = root.resolve(secondPath);
        writeImage(first, imageIoFormat, width, height);
        Files.copy(first, second);
        return catalogFixture(root, directoryName, List.of(firstPath, secondPath));
    }

    private Fixture createTextFixture(String directoryName, String relativePath, String text) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(directoryName));
        Files.writeString(root.resolve(relativePath), text);
        return catalogFixture(root, directoryName, List.of(relativePath));
    }

    private Fixture catalogFixture(Path root, String name, List<String> relativePaths) throws Exception {
        Path exactRoot = root.toRealPath();
        Source source = catalogRepository.insert(new Source(
                null, name, exactRoot.toString(), exactRoot.toString(), 0, 1, 1));
        LocationPath rootLocation = LocationPathParser.parse(LocationDialect.UNIX, exactRoot.toString());
        String contextId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String volumeId = "11111111-2222-3333-4444-555555555555";
        var contextEvidence = new MacOsApfsLocationContextEvidence(1,
                MacOsApfsLocationContextEvidence.PROFILE, 1,
                rootLocation, LocationKeyCodec.encode(rootLocation), "apfs", volumeId, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, contextId, 1, contextEvidence));
        jdbcTemplate.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 1, ?, 1, 1)
                """, contextId, new LocationPathCodec().encode(rootLocation),
                LocationKeyCodec.encode(rootLocation).value(), acceptance);
        var rootEvidence = new MacOsApfsSourceRootEvidence(1,
                MacOsApfsSourceRootEvidence.PROFILE, 1, contextId, 1, 1,
                rootLocation, LocationKeyCodec.encode(rootLocation), volumeId, "10",
                new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(
                new SourceBindingEvidence(1, source.id(), rootEvidence));
        jdbcTemplate.update("""
                UPDATE source SET root_path_key = ?, root_path_dialect = 'unix',
                    bound_location_context_id = ?, binding_evidence_json = ?, location_revision = 1
                WHERE id = ?
                """, LocationKeyCodec.encode(rootLocation).value(), contextId, binding, source.id());
        Path firstPath = exactRoot.resolve(relativePaths.getFirst());
        BasicFileAttributes firstAttributes = Files.readAttributes(firstPath, BasicFileAttributes.class);
        ContentRecord content = catalogRepository.insert(
                new ContentRecord(null, firstAttributes.size(), 1));
        for (String relativePath : relativePaths) {
            Path file = exactRoot.resolve(relativePath);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            assertEquals(content.sizeBytes(), attributes.size());
            Instant modified = attributes.lastModifiedTime().toInstant();
            LocationPath fileLocation = rootLocation.append(relativePath);
            FileEntry entry = catalogRepository.insert(new FileEntry(
                    null, "RESOLVED", contextId,
                    new LocationPathCodec().encode(fileLocation),
                    LocationKeyCodec.encode(fileLocation).value(), content.id(),
                    attributes.size(), modified.getEpochSecond(), modified.getNano(),
                    FileExtensionNormalizer.fromRelativePath(relativePath), 0, 1, 1));
            jdbcTemplate.update("""
                    INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                        path_key, applicability_status, presence_status,
                        observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                        observed_source_location_revision, observed_location_context_revision)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 'PRESENT', 0, 1, 1, 1, 1)
                    """, source.id(), entry.id(), relativePath, relativePath);
        }
        return new Fixture(source, content,
                relativePaths.stream().map(exactRoot::resolve).toList());
    }

    private static void writeImage(Path file, String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, format, file.toFile()), "No ImageIO writer for " + format);
    }

    private void assertCompletedArtifact(long contentRecordId, MediaMetadataResult expected) {
        AnalysisRecord analysis = analysisRepository.findAnalysisRecordByCacheKey(
                contentRecordId,
                MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                ImageIoMediaMetadataDefinition.ANALYZER_ID,
                ImageIoMediaMetadataDefinition.ANALYZER_VERSION,
                ImageIoMediaMetadataDefinition.CONFIGURATION_VERSION,
                ImageIoMediaMetadataDefinition.CONFIGURATION_HASH).orElseThrow();
        assertEquals("COMPLETED", analysis.status());
        assertEquals(expected, metadataCache.findReusableResult(
                contentRecordId, ImageIoMediaMetadataDefinition.definition()).orElseThrow());
    }

    private long metadataArtifactCount(long contentRecordId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM analysis_record
                WHERE content_record_id = ? AND analysis_type = 'MEDIA_METADATA'
                """, Long.class, contentRecordId);
    }

    private static MediaMetadataResult available(String format, int width, int height) {
        return new AvailableMediaMetadata(
                1, MediaKind.IMAGE, new ImageMediaMetadata(format, width, height), null);
    }

    private static MediaMetadataResult unsupported() {
        return new UnsupportedMediaMetadata(1);
    }

    private record Fixture(Source source, ContentRecord content, List<Path> paths) {
    }
}
