package io.github.topher6835.mediacompare.library;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.preview.PreviewAssetRepository;
import io.github.topher6835.mediacompare.preview.SmallThumbnailDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:duplicate-visual-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExactDuplicateVisualApiTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired AnalysisRepository analysis;
    @Autowired MediaMetadataResultCodec codec;
    @Autowired PreviewAssetRepository assets;
    @Autowired MockMvc mvc;
    MediaLibraryTestFixtures fixture;

    @BeforeEach
    void seed() {
        MediaLibraryTestFixtures.clear(jdbc);
        fixture = new MediaLibraryTestFixtures(jdbc, catalog, analysis, codec);
    }

    @Test
    void supportedExactImageUsesOnePublishedRepresentativeAndPreservesGroupCounts() throws Exception {
        var first = fixture.image("png", "a.png");
        var second = fixture.image("png", "b.png");
        String digest = "%064x".formatted(801);
        fixture.hash(first.currentContentId(), digest);
        fixture.hash(second.currentContentId(), digest);
        var asset = assets.insert(fixture.thumbnail(first, SmallThumbnailDefinition.definition()));
        mvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].digestHex").value(digest))
                .andExpect(jsonPath("$.groups[0].presentOccurrenceCount").value(2))
                .andExpect(jsonPath("$.groups[0].potentialStorageSavingsBytes").value(42))
                .andExpect(jsonPath("$.groups[0].representative.fileEntryId").value(first.id()))
                .andExpect(jsonPath("$.groups[0].representative.thumbnail.assetKey").value(asset.assetKey()));
        mvc.perform(get("/api/exact-duplicate-groups/{digest}", digest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.representative.fileEntryId").value(first.id()))
                .andExpect(jsonPath("$.representative.thumbnail.assetKey").value(asset.assetKey()))
                .andExpect(jsonPath("$.occurrences.length()").value(2));
    }

    @Test
    void unsupportedHeicAndNonImageGroupsRemainVisibleWithoutRepresentativePreview() throws Exception {
        groupWithoutMetadata("first.HEIC", "second.heic", 802);
        groupWithoutMetadata("first.pdf", "second.pdf", 803);
        mvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[0].representative").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.groups[1].representative").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.groups[0].presentOccurrenceCount").value(2));
        mvc.perform(get("/api/exact-duplicate-groups/{digest}", "%064x".formatted(802)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.representative").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/exact-duplicate-groups/{digest}", "%064x".formatted(803)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.representative").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void detailExposesValidatedFullPathForEveryPhysicalMember() throws Exception {
        var first = fixture.image("jpeg", "camera/a.jpg");
        var second = fixture.image("jpeg", "copies/b.jpg");
        String digest = "%064x".formatted(804);
        fixture.hash(first.currentContentId(), digest);
        fixture.hash(second.currentContentId(), digest);
        String root = "/images/" + fixture.contextId;
        jdbc.update("UPDATE source SET root_path=?, root_path_key=? WHERE id=?", root,
                LocationKeyCodec.encode(fixture.anchor).value(), fixture.primary.id());
        mvc.perform(get("/api/exact-duplicate-groups/{digest}", digest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences.length()").value(2))
                .andExpect(jsonPath("$.occurrences[0].absolutePath").value(root + "/camera/a.jpg"))
                .andExpect(jsonPath("$.occurrences[1].absolutePath").value(root + "/copies/b.jpg"));
    }

    private void groupWithoutMetadata(String firstPath, String secondPath, long digestValue) {
        var first = fixture.occurrence(catalog.insert(new ContentRecord(null, 42, 1)).id(), firstPath);
        var second = fixture.occurrence(catalog.insert(new ContentRecord(null, 42, 1)).id(), secondPath);
        String digest = "%064x".formatted(digestValue);
        fixture.hash(first.currentContentId(), digest);
        fixture.hash(second.currentContentId(), digest);
    }
}
