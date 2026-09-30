package io.github.topher6835.mediacompare.library;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.preview.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:library-api-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaLibraryApiTests {
    @TempDir static Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired AnalysisRepository analysis;
    @Autowired MediaMetadataResultCodec codec;
    @Autowired PreviewAssetRepository assets;
    @Autowired MockMvc mvc;
    @Autowired GetOnlyScheduler scheduler;
    MediaLibraryTestFixtures fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("media-compare.preview-cache-root", () -> directory.resolve("cache").toString());
    }

    @BeforeEach
    void seed() {
        MediaLibraryTestFixtures.clear(jdbc);
        fixture = new MediaLibraryTestFixtures(jdbc, catalog, analysis, codec);
        scheduler.calls = 0;
    }

    @Test
    void responseShapeAndPublishedMetadataAreDatabaseOnlyWithNoStateChanges() throws Exception {
        var entry = fixture.image("jpeg", "folder/example.png");
        var asset = assets.insert(fixture.thumbnail(entry, SmallThumbnailDefinition.definition()));
        var before = snapshot();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get("/api/media-library/items"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].fileEntryId").value(entry.id()))
                    .andExpect(jsonPath("$.items[0].contentRecordId").value(entry.currentContentId()))
                    .andExpect(jsonPath("$.items[0].sourceId").value(fixture.primary.id()))
                    .andExpect(jsonPath("$.items[0].sourceName").value("primary"))
                    .andExpect(jsonPath("$.items[0].relativePath").value("folder/example.png"))
                    .andExpect(jsonPath("$.items[0].displayName").value("example.png"))
                    .andExpect(jsonPath("$.items[0].extensionKey").value("png"))
                    .andExpect(jsonPath("$.items[0].sizeBytes").value(42))
                    .andExpect(jsonPath("$.items[0].format").value("jpeg"))
                    .andExpect(jsonPath("$.items[0].encodedWidth").value(600))
                    .andExpect(jsonPath("$.items[0].encodedHeight").value(400))
                    .andExpect(jsonPath("$.items[0].sourceCount").value(1))
                    .andExpect(jsonPath("$.items[0].generationSupport").value("SUPPORTED"))
                    .andExpect(jsonPath("$.items[0].thumbnail.state").value("PUBLISHED"))
                    .andExpect(jsonPath("$.items[0].thumbnail.assetKey").value(asset.assetKey()))
                    .andExpect(jsonPath("$.items[0].thumbnail.url").value("/api/previews/" + asset.assetKey()))
                    .andExpect(jsonPath("$.items[0].thumbnail.width").value(120))
                    .andExpect(jsonPath("$.items[0].thumbnail.height").value(80))
                    .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.items[0].locationPath").doesNotExist())
                    .andExpect(jsonPath("$.items[0].membershipId").doesNotExist());
        }
        assertEquals(before, snapshot());
        assertFalse(Files.exists(directory.resolve("cache")));
        assertEquals(0, scheduler.calls);
    }

    @Test
    void missingThumbnailContainsExplicitNullReferenceFields() throws Exception {
        fixture.image("gif", "image.gif");
        mvc.perform(get("/api/media-library/items"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].generationSupport").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.items[0].thumbnail.state").value("MISSING"))
                .andExpect(jsonPath("$.items[0].thumbnail.assetKey").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].thumbnail.url").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].thumbnail.width").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].thumbnail.height").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void metadataFreeHeicStillReturnsCatalogIdentityAndHonestNullEnrichment() throws Exception {
        long contentId = catalog.insert(new ContentRecord(null, 42, 1)).id();
        var entry = fixture.occurrence(contentId, "camera/image.HEIC");
        mvc.perform(get("/api/media-library/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fileEntryId").value(entry.id()))
                .andExpect(jsonPath("$.items[0].contentRecordId").value(contentId))
                .andExpect(jsonPath("$.items[0].sourceName").value("primary"))
                .andExpect(jsonPath("$.items[0].displayName").value("image.HEIC"))
                .andExpect(jsonPath("$.items[0].extensionKey").value("heic"))
                .andExpect(jsonPath("$.items[0].sizeBytes").value(42))
                .andExpect(jsonPath("$.items[0].format").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].encodedWidth").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].encodedHeight").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].generationSupport").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.items[0].thumbnail.state").value("MISSING"));
    }

    @Test
    void itemDetailLoadsByPhysicalIdAndReturns404ForAbsentOrUntrustedItem() throws Exception {
        var jpeg = fixture.image("jpeg", "camera/photo.jpg");
        mvc.perform(get("/api/media-library/items/{id}", jpeg.id()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.fileEntryId").value(jpeg.id()))
                .andExpect(jsonPath("$.format").value("jpeg"))
                .andExpect(jsonPath("$.encodedWidth").value(600));
        mvc.perform(get("/api/media-library/items/{id}", 999999)).andExpect(status().isNotFound());
        jdbc.update("UPDATE source_membership SET presence_status='MISSING' WHERE file_entry_id=?", jpeg.id());
        mvc.perform(get("/api/media-library/items/{id}", jpeg.id())).andExpect(status().isNotFound());
    }

    @Test
    void heicDetailHasNullMetadataAndValidatedFullPath() throws Exception {
        long contentId = catalog.insert(new ContentRecord(null, 42, 1)).id();
        var heic = fixture.occurrence(contentId, "camera/photo.HEIC");
        String root = "/images/" + fixture.contextId;
        jdbc.update("UPDATE source SET root_path=?, root_path_key=? WHERE id=?", root,
                io.github.topher6835.mediacompare.location.LocationKeyCodec.encode(fixture.anchor).value(),
                fixture.primary.id());
        mvc.perform(get("/api/media-library/items/{id}", heic.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("photo.HEIC"))
                .andExpect(jsonPath("$.format").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.absolutePath").value(root + "/camera/photo.HEIC"))
                .andExpect(jsonPath("$.exactSet").value(org.hamcrest.Matchers.nullValue()));
        jdbc.update("UPDATE source SET root_path_key='invalid' WHERE id=?", fixture.primary.id());
        mvc.perform(get("/api/media-library/items/{id}", heic.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absolutePath").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void exactBadgeCountsPresentPhysicalCopiesAndSingletonHasNoBadge() throws Exception {
        var first = fixture.image("png", "first.png");
        var second = fixture.image("png", "second.png");
        var singleton = fixture.image("jpeg", "single.jpg");
        String digest = "%064x".formatted(145);
        fixture.hash(first.currentContentId(), digest);
        fixture.hash(second.currentContentId(), digest);
        mvc.perform(get("/api/media-library/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].exactSet.digestHex").value(digest))
                .andExpect(jsonPath("$.items[0].exactSet.physicalCopyCount").value(2))
                .andExpect(jsonPath("$.items[1].exactSet.physicalCopyCount").value(2))
                .andExpect(jsonPath("$.items[2].fileEntryId").value(singleton.id()))
                .andExpect(jsonPath("$.items[2].exactSet").value(org.hamcrest.Matchers.nullValue()));
        jdbc.update("UPDATE source_membership SET presence_status='MISSING' WHERE file_entry_id=?", second.id());
        mvc.perform(get("/api/media-library/items/{id}", first.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactSet").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void defaultAndExplicitPaginationUseTheLastReturnedPhysicalIdAsCursor() throws Exception {
        var ids = new java.util.ArrayList<Long>();
        for (int index = 0; index < 51; index++) ids.add(fixture.image("png", "image-" + index + ".png").id());
        mvc.perform(get("/api/media-library/items"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.nextCursor").value(ids.get(49)));
        mvc.perform(get("/api/media-library/items").param("afterFileEntryId", ids.get(49).toString()).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].fileEntryId").value(ids.getLast()))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/media-library/items").param("afterFileEntryId", "0").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(ids.get(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "bad", "9223372036854775808"})
    void invalidCursorsReturn400(String cursor) throws Exception {
        mvc.perform(get("/api/media-library/items").param("afterFileEntryId", cursor))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, scheduler.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "201", "bad", "2147483648"})
    void invalidLimitsReturn400(String limit) throws Exception {
        mvc.perform(get("/api/media-library/items").param("limit", limit)).andExpect(status().isBadRequest());
    }

    @Test
    void corruptCompatibleImageMetadataReturnsAnEmptySafe500WithoutGenerating() throws Exception {
        fixture.image("png", "image.png");
        jdbc.update("UPDATE analysis_record SET result_json = '{broken'");
        var before = snapshot();
        mvc.perform(get("/api/media-library/items")).andExpect(status().isInternalServerError())
                .andExpect(content().string("")).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(before, snapshot());
        assertEquals(0, scheduler.calls);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GetOnlyConfiguration {
        @Bean @Primary
        GetOnlyScheduler getOnlyScheduler(SmallThumbnailService thumbnails, CatalogOwnership ownership) {
            return new GetOnlyScheduler(thumbnails, ownership);
        }
    }

    static class GetOnlyScheduler extends ThumbnailScheduler {
        int calls;
        GetOnlyScheduler(SmallThumbnailService thumbnails, CatalogOwnership ownership) {
            super(thumbnails, ownership);
        }
        @Override public Result schedule(long fileEntryId) {
            calls++;
            throw new AssertionError("Album GET attempted scheduling");
        }
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var snapshot = new LinkedHashMap<String, List<Map<String, Object>>>();
        var tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", String.class);
        for (String table : tables) snapshot.put(table, jdbc.queryForList("SELECT * FROM " + table));
        return snapshot;
    }
}
