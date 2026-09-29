package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.library.MediaLibraryTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:library-thumbnail-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaLibraryThumbnailIntegrationTests {
    @TempDir static Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired AnalysisRepository analysis;
    @Autowired MediaMetadataResultCodec codec;
    @Autowired PreviewAssetRepository assets;
    @Autowired MockMvc mvc;
    @Autowired GenerationControl control;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("media-compare.preview-cache-root", () -> directory.resolve("cache").toString());
    }

    @BeforeEach
    void clear() {
        control.barriers.clear();
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM analysis_record");
        jdbc.update("DELETE FROM content_record");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void explicitPostGeneratesAndRepairsMissingFileWhileGetOnlyProjectsTheExistingRow() throws Exception {
        var fixture = fixture();
        var first = barrier(Long.MAX_VALUE);
        var second = barrier(Long.MAX_VALUE - 1);
        try {
            postIds(fixture.entryId(), first.id());
            first.awaitEntered();
            var asset = assets.findCurrent(fixture.entryId(), SmallThumbnailDefinition.definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow();
            Path output = directory.resolve("cache").resolve(asset.relativePath());
            byte[] bytes = Files.readAllBytes(output);
            assertEquals(1, rowCount());
            mvc.perform(get("/api/media-library/items")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].thumbnail.state").value("PUBLISHED"))
                    .andExpect(jsonPath("$.items[0].thumbnail.assetKey").value(asset.assetKey()));
            Files.delete(output);
            mvc.perform(get("/api/media-library/items")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].thumbnail.state").value("PUBLISHED"));
            assertFalse(Files.exists(output));
            mvc.perform(get("/api/previews/" + asset.assetKey())).andExpect(status().isNotFound());
            postIds(fixture.entryId(), second.id());
            first.release().countDown();
            second.awaitEntered();
            assertEquals(asset, assets.findByAssetKey(asset.assetKey()).orElseThrow());
            assertEquals(1, rowCount());
            assertArrayEquals(bytes, Files.readAllBytes(output));
            mvc.perform(get("/api/previews/" + asset.assetKey())).andExpect(status().isOk())
                    .andExpect(content().contentType("image/png")).andExpect(content().bytes(bytes));
        } finally {
            first.release().countDown();
            second.release().countDown();
        }
    }

    @Test
    void queuedGenerationUsesTheFileEntryEvidenceAtExecutionTime() throws Exception {
        var fixture = fixture();
        var first = barrier(Long.MAX_VALUE - 2);
        var second = barrier(Long.MAX_VALUE - 3);
        try {
            postIds(first.id(), fixture.entryId(), second.id());
            first.awaitEntered();
            assertEquals(0, rowCount());
            jdbc.update("UPDATE file_entry SET observation_revision = 1 WHERE id = ?", fixture.entryId());
            jdbc.update("UPDATE source_membership SET observed_file_entry_revision = 1 WHERE file_entry_id = ?", fixture.entryId());
            first.release().countDown();
            second.awaitEntered();
            var asset = assets.findCurrent(fixture.entryId(), SmallThumbnailDefinition.definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow();
            assertEquals(1, asset.evidence().observationRevision());
            assertEquals(1, rowCount());
        } finally {
            first.release().countDown();
            second.release().countDown();
        }
    }

    private ThumbnailCatalogFixture fixture() throws Exception {
        var fixture = ThumbnailCatalogFixture.create(directory, jdbc, catalog,
                ThumbnailImageFixtures.encoded("PNG", 600, 400, false));
        long contentId = jdbc.queryForObject("SELECT current_content_id FROM file_entry WHERE id = ?", Long.class, fixture.entryId());
        MediaLibraryTestFixtures.imageMetadata(analysis, codec, contentId, "png");
        return fixture;
    }

    private Barrier barrier(long id) {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        control.barriers.put(id, new Barrier(id, entered, release));
        return new Barrier(id, entered, release);
    }

    private void postIds(long... ids) throws Exception {
        String list = java.util.Arrays.stream(ids).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/api/media-library/thumbnails").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileEntryIds\":[" + list + "]}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.results[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("QUEUED"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledConfiguration {
        @Bean GenerationControl generationControl() { return new GenerationControl(); }
        @Bean @Primary
        ThumbnailScheduler controlledScheduler(CatalogOwnership ownership, SmallThumbnailService thumbnails,
                GenerationControl control) {
            return new ThumbnailScheduler(ownership, id -> {
                Barrier barrier = control.barriers.get(id);
                if (barrier == null) return thumbnails.generate(id);
                barrier.entered().countDown();
                try { assertTrue(barrier.release().await(10, TimeUnit.SECONDS), "fixture barrier timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return new ThumbnailGenerationResult(ThumbnailGenerationResult.Outcome.UNSUPPORTED, null);
            });
        }
    }
    static class GenerationControl {
        final java.util.Map<Long, Barrier> barriers = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private long rowCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM preview_asset", Long.class); }
    private record Barrier(long id, CountDownLatch entered, CountDownLatch release) {
        void awaitEntered() throws InterruptedException { assertTrue(entered.await(10, TimeUnit.SECONDS)); }
    }
}
