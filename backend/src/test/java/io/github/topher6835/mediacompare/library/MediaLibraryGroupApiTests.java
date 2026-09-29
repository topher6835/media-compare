package io.github.topher6835.mediacompare.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.preview.PreviewAssetRepository;
import io.github.topher6835.mediacompare.preview.SmallThumbnailDefinition;
import io.github.topher6835.mediacompare.preview.SmallThumbnailService;
import io.github.topher6835.mediacompare.preview.ThumbnailScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:library-group-api-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaLibraryGroupApiTests {
    private static final List<String> PROTECTED_TABLES = List.of("content_record", "analysis_record", "content_hash",
            "media_relationship", "preview_asset", "file_entry", "source_membership");
    @TempDir static Path directory;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired AnalysisRepository analysis;
    @Autowired MediaMetadataResultCodec codec;
    @Autowired PreviewAssetRepository assets;
    @Autowired MockMvc mvc;
    @Autowired ForbiddenScheduler scheduler;
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

    @AfterEach
    void verifyAndRemoveWriteGuards() {
        for (String table : PROTECTED_TABLES) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                jdbc.execute("DROP TRIGGER IF EXISTS forbid_group_" + table + "_" + operation);
            }
        }
        assertEquals(0, scheduler.calls);
        assertFalse(Files.exists(directory.resolve("cache")));
    }

    @Test
    void defaultExplicitAndRepeatedExactReturnFullRepresentativeShapeWithoutWritesOrFiles() throws Exception {
        long hidden = catalog.insert(new ContentRecord(null, 42, 1)).id();
        fixture.hash(hidden, digest(10));
        var a = fixture.image("jpeg", "folder/example.png");
        var b = fixture.image("png", "b.png");
        fixture.hash(a.currentContentId(), digest(10));
        fixture.hash(b.currentContentId(), digest(10));
        var asset = assets.insert(fixture.thumbnail(a, SmallThumbnailDefinition.definition()));
        var before = snapshot();
        for (String table : PROTECTED_TABLES) {
            for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                jdbc.execute("CREATE TRIGGER forbid_group_" + table + "_" + operation + " BEFORE " + operation
                        + " ON " + table + " BEGIN SELECT RAISE(ABORT, 'group GET must be read-only'); END");
            }
        }
        for (int repetitions = 0; repetitions < 3; repetitions++) {
            var request = get("/api/media-library/groups");
            if (repetitions > 0) request.param("relationshipType", repetitions == 1
                    ? new String[] {"EXACT"} : new String[] {"EXACT", "EXACT"});
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.groups.length()").value(1))
                    .andExpect(jsonPath("$.groups[0].length()").value(3))
                    .andExpect(jsonPath("$.groups[0].groupKeyContentRecordId").value(hidden))
                    .andExpect(jsonPath("$.groups[0].currentItemCount").value(2))
                    .andExpect(jsonPath("$.groups[0].representative.fileEntryId").value(a.id()))
                    .andExpect(jsonPath("$.groups[0].representative.contentRecordId").value(a.currentContentId()))
                    .andExpect(jsonPath("$.groups[0].representative.sourceId").value(fixture.primary.id()))
                    .andExpect(jsonPath("$.groups[0].representative.sourceName").value("primary"))
                    .andExpect(jsonPath("$.groups[0].representative.relativePath").value("folder/example.png"))
                    .andExpect(jsonPath("$.groups[0].representative.displayName").value("example.png"))
                    .andExpect(jsonPath("$.groups[0].representative.extensionKey").value("png"))
                    .andExpect(jsonPath("$.groups[0].representative.sizeBytes").value(42))
                    .andExpect(jsonPath("$.groups[0].representative.format").value("jpeg"))
                    .andExpect(jsonPath("$.groups[0].representative.encodedWidth").value(600))
                    .andExpect(jsonPath("$.groups[0].representative.encodedHeight").value(400))
                    .andExpect(jsonPath("$.groups[0].representative.sourceCount").value(1))
                    .andExpect(jsonPath("$.groups[0].representative.generationSupport").value("SUPPORTED"))
                    .andExpect(jsonPath("$.groups[0].representative.thumbnail.state").value("PUBLISHED"))
                    .andExpect(jsonPath("$.groups[0].representative.thumbnail.assetKey").value(asset.assetKey()))
                    .andExpect(jsonPath("$.groups[0].representative.thumbnail.url").value("/api/previews/" + asset.assetKey()))
                    .andExpect(jsonPath("$.groups[0].representative.thumbnail.width").value(120))
                    .andExpect(jsonPath("$.groups[0].representative.thumbnail.height").value(80))
                    .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        }
        assertEquals(before, snapshot());
        assertEquals(List.of(), before.get("media_relationship"));
    }

    @Test
    void emptyCatalogReturnsAnEmptyNoStorePage() throws Exception {
        mvc.perform(get("/api/media-library/groups")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.groups.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void defaultAndExplicitPagesUseRepresentativePhysicalIds() throws Exception {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 51; i++) ids.add(fixture.image("png", "image-" + i + ".png").id());
        mvc.perform(get("/api/media-library/groups")).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(50))
                .andExpect(jsonPath("$.nextCursor").value(ids.get(49)));
        mvc.perform(get("/api/media-library/groups").param("afterRepresentativeFileEntryId", ids.get(49).toString())
                .param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].representative.fileEntryId").value(ids.getLast()))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/media-library/groups").param("afterRepresentativeFileEntryId", "0").param("limit", "200"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups.length()").value(51))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CROP", "RESIZED", "EDITED", "VIDEO_OVERLAP", "VIDEO_SEGMENT_SEQUENCE", "unknown", "latest", ""})
    void unavailableOrMalformedTypesReturnAnEmptyNoStore400(String type) throws Exception {
        mvc.perform(get("/api/media-library/groups").param("relationshipType", type))
                .andExpect(status().isBadRequest()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/media-library/groups").param("relationshipType", "EXACT", type))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "bad", "9223372036854775808"})
    void invalidCursorsReturn400(String cursor) throws Exception {
        mvc.perform(get("/api/media-library/groups").param("afterRepresentativeFileEntryId", cursor))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "201", "bad", "2147483648"})
    void invalidLimitsReturn400(String limit) throws Exception {
        mvc.perform(get("/api/media-library/groups").param("limit", limit))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void corruptCompatibleImageMetadataReturnsAnEmptySafe500() throws Exception {
        fixture.image("png", "image.png");
        jdbc.update("UPDATE analysis_record SET result_json = '{broken'");
        var before = snapshot();
        mvc.perform(get("/api/media-library/groups")).andExpect(status().isInternalServerError())
                .andExpect(content().string("")).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "algorithm", "digest", "configuration", "size"})
    void invalidCurrentShaAuthorityReturnsAnEmptySafe500ForDefaultOrExplicitExact(String mutation) throws Exception {
        var a = fixture.image("png", "a.png");
        var b = fixture.image("png", "b.png");
        fixture.hash(a.currentContentId(), digest(10));
        fixture.hash(b.currentContentId(), digest(10));
        switch (mutation) {
            case "missing" -> jdbc.update("DELETE FROM content_hash");
            case "algorithm" -> jdbc.update("UPDATE content_hash SET algorithm = 'SHA-1'");
            case "digest" -> jdbc.update("UPDATE content_hash SET digest_hex = 'invalid'");
            case "configuration" -> jdbc.update("UPDATE analysis_record SET configuration_json = ' {} ' "
                    + "WHERE analysis_type = 'CONTENT_HASH'");
            case "size" -> jdbc.update("UPDATE content_record SET size_bytes = 43 WHERE id = ?", b.currentContentId());
            default -> throw new IllegalArgumentException(mutation);
        }
        var before = snapshot();
        for (int i = 0; i < 2; i++) {
            var request = get("/api/media-library/groups");
            if (i == 1) request.param("relationshipType", "EXACT");
            mvc.perform(request).andExpect(status().isInternalServerError()).andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertEquals(before, snapshot());
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : PROTECTED_TABLES) result.put(table, jdbc.queryForList("SELECT * FROM " + table));
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GetOnlyConfiguration {
        @Bean @Primary
        ForbiddenScheduler forbiddenScheduler(SmallThumbnailService thumbnails, CatalogOwnership ownership) {
            return new ForbiddenScheduler(thumbnails, ownership);
        }
    }

    static class ForbiddenScheduler extends ThumbnailScheduler {
        int calls;

        ForbiddenScheduler(SmallThumbnailService thumbnails, CatalogOwnership ownership) {
            super(thumbnails, ownership);
        }

        @Override
        public Result schedule(long fileEntryId) {
            calls++;
            throw new AssertionError("Grouped GET attempted thumbnail scheduling");
        }
    }

    private static String digest(long value) {
        return "%064x".formatted(value);
    }
}
