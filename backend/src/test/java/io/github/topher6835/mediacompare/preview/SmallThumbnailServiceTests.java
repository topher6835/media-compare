package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.preview.ThumbnailImageFixtures.*;
import static io.github.topher6835.mediacompare.preview.ThumbnailGenerationResult.Outcome.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:thumbnail-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SmallThumbnailServiceTests {
    @TempDir static Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired MediaMetadataCandidateRepository candidates;
    @Autowired MediaMetadataFileEvidenceValidator evidenceValidator;
    @Autowired PreviewAssetRepository assets;
    @Autowired PreviewCacheWriter cache;
    @Autowired ThumbnailPublisher publisher;
    @Autowired SmallThumbnailService service;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("media-compare.preview-cache-root", () -> directory.resolve("cache").toString());
    }

    @BeforeEach
    void clear() throws Exception {
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM content_record");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
        Path root = directory.resolve("cache");
        if (Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    void generatesThenReusesWithoutOpeningOriginalAndServesTheRealPng() throws Exception {
        var fixture = fixture(encoded("JPEG", 1000, 500, false));
        byte[] originalBytes = Files.readAllBytes(fixture.original());
        var originalTime = Files.getLastModifiedTime(fixture.original());
        var count = new AtomicInteger();
        var observed = service(counting(count, () -> {}));
        var first = observed.generate(fixture.entryId());
        assertEquals(GENERATED, first.outcome());
        var asset = first.asset();
        assertEquals(320, asset.pixelWidth());
        assertEquals(160, asset.pixelHeight());
        assertEquals("image/png", asset.mediaType());
        assertEquals(PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, asset.assetKey(), "png"), asset.relativePath());
        assertEquals(Files.size(path(asset)), asset.assetSizeBytes());
        assertEquals(asset, assets.findCurrent(fixture.entryId(), SmallThumbnailDefinition.definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
        assertArrayEquals(originalBytes, Files.readAllBytes(fixture.original()));
        assertEquals(originalTime, Files.getLastModifiedTime(fixture.original()));
        var finalTime = Files.getLastModifiedTime(path(asset));
        // Reuse requires durable trust, but does not need to reopen even a now-offline original.
        Files.delete(fixture.original());
        var second = observed.generate(fixture.entryId());
        assertEquals(REUSED, second.outcome());
        assertEquals(asset, second.asset());
        assertEquals(1, count.get());
        assertEquals(finalTime, Files.getLastModifiedTime(path(asset)));
        mvc.perform(get("/api/previews/" + asset.assetKey())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(Files.readAllBytes(path(asset))));
        assertEquals(1, rowCount());
        assertNoTemporary();
    }

    @Test
    void endToEndExifJpegUsesDisplayedPortraitDimensions() throws Exception {
        var fixture = fixture(withOrientation(encoded("JPEG", 800, 400, false), 6, ByteOrder.BIG_ENDIAN));
        var asset = service.generate(fixture.entryId()).asset();
        assertEquals(160, asset.pixelWidth());
        assertEquals(320, asset.pixelHeight());
        int color = ImageIO.read(path(asset).toFile()).getRGB(10, 10);
        assertTrue((color & 0xff) > ((color >> 16) & 0xff) + 100);
    }

    @Test
    void missingFinalFileIsRecreatedWithTheSameMetadataRow() throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, true));
        var asset = service.generate(fixture.entryId()).asset();
        byte[] bytes = Files.readAllBytes(path(asset));
        Files.delete(path(asset));
        var recreated = service.generate(fixture.entryId());
        assertEquals(GENERATED, recreated.outcome());
        assertEquals(asset, recreated.asset());
        assertArrayEquals(bytes, Files.readAllBytes(path(asset)));
        assertEquals(1, rowCount());
        assertEquals(0x60, ImageIO.read(path(asset).toFile()).getRGB(2, 2) >>> 24);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "format", "dimensions", "persisted-dimensions", "missing-metadata-mismatch"})
    void inconsistentImmutableAssetsFailClosedAndAreNeverReplaced(String mutation) throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        var asset = service.generate(fixture.entryId()).asset();
        switch (mutation) {
            case "bytes" -> Files.write(path(asset), new byte[] {1,2,3});
            case "format" -> {
                byte[] bytes = new byte[(int) asset.assetSizeBytes()];
                Arrays.fill(bytes, (byte) 7);
                Files.write(path(asset), bytes);
            }
            case "dimensions" -> {
                byte[] wrong = encoded("PNG", 20, 40, false);
                Files.write(path(asset), Arrays.copyOf(wrong, (int) asset.assetSizeBytes()));
            }
            case "persisted-dimensions", "missing-metadata-mismatch" -> {
                jdbc.update("UPDATE preview_asset SET pixel_width = pixel_width + 1");
                if (mutation.equals("missing-metadata-mismatch")) Files.delete(path(asset));
            }
        }
        byte[] before = Files.exists(path(asset)) ? Files.readAllBytes(path(asset)) : null;
        assertThrows(ThumbnailGenerationException.class, () -> service.generate(fixture.entryId()));
        if (before == null) assertFalse(Files.exists(path(asset)));
        else assertArrayEquals(before, Files.readAllBytes(path(asset)));
        assertEquals(1, rowCount());
        assertNoTemporary();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BMP", "GIF", "text"})
    void unsupportedContentPublishesNothing(String format) throws Exception {
        byte[] bytes = format.equals("text") ? new byte[] {1,2,3,4} : encoded(format, 20, 10, false);
        var fixture = fixture(bytes);
        var result = service.generate(fixture.entryId());
        assertEquals(UNSUPPORTED, result.outcome());
        assertNull(result.asset());
        assertNoSuccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JPEG", "PNG"})
    void corruptSupportedImagesPublishNothing(String format) throws Exception {
        var fixture = fixture(Arrays.copyOf(encoded(format, 80, 40, false), 20));
        assertThrows(ThumbnailGenerationException.class, () -> service.generate(fixture.entryId()));
        assertNoSuccess();
    }

    @Test
    void oversizedEncodedImageFailsWithoutPublishingOrLeavingTemporaryOutput() throws Exception {
        var fixture = fixture(oversizedPngHeader());
        assertThrows(ThumbnailGenerationException.class, () -> service.generate(fixture.entryId()));
        assertNoSuccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "retired", "source", "observed-file", "observed-source", "observed-context",
            "context", "unbound", "content-size", "mtime-null", "unresolved", "context-retired", "context-unaccepted"})
    void untrustedOrIncompleteCandidatesAreUnavailable(String mutation) throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        mutate(fixture, mutation);
        assertTrue(candidates.findCurrentOccurrence(fixture.entryId()).isEmpty());
        assertThrows(ThumbnailGenerationException.class, () -> service.generate(fixture.entryId()));
        assertNoSuccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "revision", "size", "second", "nano", "membership", "source", "context",
            "content-size", "missing", "retired", "unbound", "observed-source", "observed-context", "context-retired", "context-unaccepted"})
    void publicationRechecksDurableEvidenceAfterRendering(String mutation) throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        var observed = service(counting(new AtomicInteger(), () -> mutate(fixture, mutation)));
        assertThrows(RuntimeException.class, () -> observed.generate(fixture.entryId()));
        assertEquals(0, rowCount());
        assertNoTemporary();
    }

    @Test
    void changedOriginalBetweenPreAndPostReadPublishesNothing() throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        var observed = service(new SmallThumbnailRenderer() {
            @Override public Optional<Dimensions> render(Path source, Path output) throws IOException {
                var rendered = super.render(source, output);
                Files.write(source, new byte[] {1,2,3});
                return rendered;
            }
        });
        assertThrows(StaleMediaMetadataEvidenceException.class, () -> observed.generate(fixture.entryId()));
        assertNoSuccess();
    }

    @Test
    void renderingFailureCleansItsTemporaryFile() throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        var observed = service(new SmallThumbnailRenderer() {
            @Override public Optional<Dimensions> render(Path source, Path output) throws IOException {
                Files.write(output, new byte[] {1,2,3});
                throw new IOException("injected renderer failure");
            }
        });
        assertThrows(ThumbnailGenerationException.class, () -> observed.generate(fixture.entryId()));
        assertNoSuccess();
    }

    @Test
    void overlappingSourcesSelectOneRouteAndReuseTheSamePhysicalIdentity() throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        fixture.overlap(jdbc, catalog);
        var selected = candidates.findCurrentOccurrence(fixture.entryId()).orElseThrow();
        assertEquals(fixture.sourceId(), selected.sourceId());
        var asset = service.generate(fixture.entryId()).asset();
        jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE source_id = ?", fixture.sourceId());
        assertNotEquals(selected.membershipId(), candidates.findCurrentOccurrence(fixture.entryId()).orElseThrow().membershipId());
        assertEquals(asset, service.generate(fixture.entryId()).asset());
        assertEquals(1, rowCount());
    }

    @Test
    void equivalentConcurrentCallsRenderOnceAndPublishOneRowWithoutTimingAssumptions() throws Exception {
        var fixture = fixture(encoded("JPEG", 1600, 800, false));
        var count = new AtomicInteger();
        var observed = service(counting(count, () -> {}));
        try (var executor = Executors.newFixedThreadPool(3)) {
            Callable<ThumbnailGenerationResult> call = () -> observed.generate(fixture.entryId());
            var futures = executor.invokeAll(java.util.List.of(call, call, call));
            var results = new java.util.ArrayList<ThumbnailGenerationResult>();
            for (var future : futures) results.add(future.get());
            assertEquals(1, results.stream().filter(result -> result.outcome() == GENERATED).count());
            assertEquals(2, results.stream().filter(result -> result.outcome() == REUSED).count());
            assertEquals(1, results.stream().map(result -> result.asset().id()).distinct().count());
        }
        assertEquals(1, count.get());
        assertEquals(1, rowCount());
        assertNoTemporary();
    }

    @Test
    void equivalentFinalFileAndDatabasePublicationAreIdempotent() throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        var asset = service.generate(fixture.entryId()).asset();
        var candidate = candidates.findCurrentOccurrence(fixture.entryId()).orElseThrow();
        Path temp = cache.createTemporary(asset.relativePath(), asset.assetKey());
        try {
            Files.write(temp, Files.readAllBytes(path(asset)));
            assertFalse(cache.publish(temp, asset));
            assertEquals(asset, publisher.publishIfStillCurrent(candidate, asset));
            var different = new PreviewAsset(null, asset.assetKey(), asset.evidence(), asset.kind(), asset.definition(),
                    asset.relativePath(), asset.mediaType(), asset.pixelWidth() + 1, asset.pixelHeight(), asset.assetSizeBytes(), 1);
            assertThrows(IOException.class, () -> publisher.publishIfStillCurrent(candidate, different));
        } finally { Files.delete(temp); }
        assertEquals(1, rowCount());
    }

    @Test
    void unrelatedOrphanTargetIsNeverOverwritten() throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        var candidate = candidates.findCurrentOccurrence(fixture.entryId()).orElseThrow();
        String key = PreviewAssetKey.compute(ThumbnailPublisher.evidence(candidate), PreviewKind.SMALL_THUMBNAIL,
                SmallThumbnailDefinition.definition());
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png");
        Path temp = cache.createTemporary(relative, key);
        Path target = temp.getParent().resolve(key + ".png");
        byte[] occupant = encoded("PNG", 40, 20, true);
        Files.write(target, occupant);
        Files.delete(temp);
        assertThrows(ThumbnailGenerationException.class, () -> service.generate(fixture.entryId()));
        assertArrayEquals(occupant, Files.readAllBytes(target));
        assertEquals(0, rowCount());
        assertNoTemporary();
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "directory", "target", "original"})
    void symbolicLinksFailClosedAtOriginalAndCacheBoundaries(String linkAt) throws Exception {
        var fixture = fixture(encoded("PNG", 40, 20, false));
        Path root = directory.resolve("cache");
        Path outside = Files.createTempDirectory(directory, "outside-");
        var candidate = candidates.findCurrentOccurrence(fixture.entryId()).orElseThrow();
        String key = PreviewAssetKey.compute(ThumbnailPublisher.evidence(candidate), PreviewKind.SMALL_THUMBNAIL,
                SmallThumbnailDefinition.definition());
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png");
        switch (linkAt) {
            case "root" -> symbolicLink(root, outside);
            case "directory" -> {
                Files.createDirectory(root);
                symbolicLink(root.resolve("small-thumbnail"), outside);
            }
            case "target" -> {
                Path temp = cache.createTemporary(relative, key);
                Files.delete(temp);
                Path file = Files.write(outside.resolve("asset.png"), encoded("PNG", 40, 20, false));
                symbolicLink(root.resolve(relative), file);
            }
            case "original" -> {
                Path file = Files.write(outside.resolve("original.png"), Files.readAllBytes(fixture.original()));
                Files.delete(fixture.original());
                symbolicLink(fixture.original(), file);
            }
        }
        assertThrows(RuntimeException.class, () -> service.generate(fixture.entryId()));
        assertEquals(0, rowCount());
        assertNoTemporary();
    }

    @Test
    void sameSizeValidPngOccupantRequiresByteEquivalence() throws Exception {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xff0000);
        var red = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "PNG", red);
        image.setRGB(0, 0, 0x0000ff);
        var blue = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "PNG", blue);
        assertEquals(red.size(), blue.size());
        var fixture = fixture(blue.toByteArray());
        var candidate = candidates.findCurrentOccurrence(fixture.entryId()).orElseThrow();
        String key = PreviewAssetKey.compute(ThumbnailPublisher.evidence(candidate), PreviewKind.SMALL_THUMBNAIL,
                SmallThumbnailDefinition.definition());
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png");
        Path temp = cache.createTemporary(relative, key);
        Path target = temp.getParent().resolve(key + ".png");
        Files.write(target, red.toByteArray());
        Files.delete(temp);
        assertThrows(ThumbnailGenerationException.class, () -> service.generate(fixture.entryId()));
        assertArrayEquals(red.toByteArray(), Files.readAllBytes(target));
        assertEquals(0, rowCount());
        assertNoTemporary();
    }

    private void symbolicLink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Host does not permit symbolic links");
        }
    }

    private ThumbnailCatalogFixture fixture(byte[] bytes) throws Exception {
        return ThumbnailCatalogFixture.create(directory, jdbc, catalog, bytes);
    }

    private SmallThumbnailService service(SmallThumbnailRenderer renderer) {
        return new SmallThumbnailService(candidates, evidenceValidator, assets, renderer, cache, publisher);
    }

    private SmallThumbnailRenderer counting(AtomicInteger count, Runnable afterRendering) {
        return new SmallThumbnailRenderer() {
            @Override public Optional<Dimensions> render(Path source, Path output) throws IOException {
                count.incrementAndGet();
                var rendered = super.render(source, output);
                afterRendering.run();
                return rendered;
            }
        };
    }

    private void mutate(ThumbnailCatalogFixture fixture, String mutation) {
        switch (mutation) {
            case "missing" -> jdbc.update("UPDATE source_membership SET presence_status = 'MISSING'");
            case "retired" -> jdbc.update("UPDATE source_membership SET applicability_status = 'RETIRED'");
            case "membership" -> jdbc.update("UPDATE source_membership SET membership_revision = membership_revision + 1");
            case "observed-file" -> jdbc.update("UPDATE source_membership SET observed_file_entry_revision = 1");
            case "observed-source" -> jdbc.update("UPDATE source_membership SET observed_source_location_revision = 2");
            case "observed-context" -> jdbc.update("UPDATE source_membership SET observed_location_context_revision = 2");
            case "source" -> jdbc.update("UPDATE source SET location_revision = location_revision + 1");
            case "context" -> jdbc.update("UPDATE location_context SET revision = revision + 1");
            case "context-retired" -> jdbc.update("UPDATE location_context SET lifecycle_status = 'RETIRED'");
            case "context-unaccepted" -> jdbc.update("UPDATE location_context SET continuity_status = 'REVIEW_REQUIRED'");
            case "unbound" -> jdbc.update("UPDATE source SET bound_location_context_id = NULL, binding_evidence_json = NULL");
            case "content-size" -> jdbc.update("UPDATE content_record SET size_bytes = size_bytes + 1");
            case "mtime-null" -> jdbc.update("UPDATE file_entry SET modified_time_epoch_second = NULL, modified_time_nano = NULL");
            case "unresolved" -> jdbc.update("UPDATE file_entry SET location_identity_status = 'UNRESOLVED', location_context_id = NULL, location_path = NULL, location_key = NULL");
            case "content" -> {
                long size = jdbc.queryForObject("SELECT size_bytes FROM file_entry WHERE id = ?", Long.class, fixture.entryId());
                var content = catalog.insert(new ContentRecord(null, size, 1));
                jdbc.update("UPDATE file_entry SET current_content_id = ?", content.id());
            }
            case "revision" -> jdbc.update("UPDATE file_entry SET observation_revision = observation_revision + 1");
            case "size" -> jdbc.update("UPDATE file_entry SET size_bytes = size_bytes + 1");
            case "second" -> jdbc.update("UPDATE file_entry SET modified_time_epoch_second = modified_time_epoch_second + 1");
            case "nano" -> jdbc.update("UPDATE file_entry SET modified_time_nano = (modified_time_nano + 1) % 1000000000");
            default -> throw new IllegalArgumentException(mutation);
        }
    }

    private Path path(PreviewAsset asset) { return directory.resolve("cache").resolve(asset.relativePath()); }
    private long rowCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM preview_asset", Long.class); }
    private void assertNoTemporary() throws Exception {
        Path root = directory.resolve("cache");
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) { assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))); }
        }
    }
    private void assertNoSuccess() throws Exception {
        assertEquals(0, rowCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record", Long.class));
        Path root = directory.resolve("cache");
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) { assertFalse(paths.anyMatch(Files::isRegularFile)); }
        }
    }
}
