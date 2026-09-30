package io.github.topher6835.mediacompare.library;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.preview.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:library-query-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaLibraryTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired AnalysisRepository analysis;
    @Autowired MediaMetadataResultCodec codec;
    @Autowired MediaLibraryRepository repository;
    @Autowired MediaLibraryService library;
    @Autowired MediaLibraryGroupService groups;
    @Autowired PreviewAssetRepository assets;
    MediaLibraryTestFixtures fixture;

    @BeforeEach
    void seed() {
        MediaLibraryTestFixtures.clear(jdbc);
        fixture = new MediaLibraryTestFixtures(jdbc, catalog, analysis, codec);
    }

    @Test
    void projectsOnePhysicalImageWithEncodedDimensionsAndPortableDisplayName() {
        var entry = fixture.image("jpeg", "folder/misleading.bin");
        var item = library.findItems(null, null).items().getFirst();
        assertEquals(entry.id(), item.fileEntryId());
        assertEquals(entry.currentContentId(), item.contentRecordId());
        assertEquals(fixture.primary.id(), item.sourceId());
        assertEquals("primary", item.sourceName());
        assertEquals("folder/misleading.bin", item.relativePath());
        assertEquals("misleading.bin", item.displayName());
        assertEquals("bin", item.extensionKey());
        assertEquals(42, item.sizeBytes());
        assertEquals("jpeg", item.format());
        assertEquals(600, item.encodedWidth());
        assertEquals(400, item.encodedHeight());
        assertEquals(1, item.sourceCount());
        assertEquals(MediaLibraryItem.GenerationSupport.SUPPORTED, item.generationSupport());
        assertEquals(ThumbnailReference.missing(), item.thumbnail());
    }

    @Test
    void overlappingSourcesUseTheLowestEligibleMembershipAndCountOnlyTrustedSources() {
        var entry = fixture.image("png", "first.png");
        var second = fixture.source("secondary");
        fixture.membership(second, entry, "nested/second.png");
        var third = fixture.source("stale");
        fixture.membership(third, entry, "third.png");
        jdbc.update("UPDATE source_membership SET observed_source_location_revision = 2 WHERE source_id = ?", third.id());
        var item = library.findItems(null, null).items().getFirst();
        assertEquals(2, item.sourceCount());
        assertEquals(fixture.primary.id(), item.sourceId());
        assertEquals("first.png", item.relativePath());
        jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE source_id = ?", fixture.primary.id());
        var page = library.findItems(null, null);
        assertEquals(1, page.items().size());
        item = page.items().getFirst();
        assertEquals(second.id(), item.sourceId());
        assertEquals("second.png", item.displayName());
        assertEquals(1, item.sourceCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "retired", "observed-file", "source-revision", "context-revision", "unbound",
            "context-retired", "context-unaccepted", "unresolved", "no-content", "no-membership"})
    void excludesUntrustedCurrentRoutes(String mutation) {
        fixture.image("png", "image.png");
        switch (mutation) {
            case "missing" -> jdbc.update("UPDATE source_membership SET presence_status = 'MISSING'");
            case "retired" -> jdbc.update("UPDATE source_membership SET applicability_status = 'RETIRED'");
            case "observed-file" -> jdbc.update("UPDATE source_membership SET observed_file_entry_revision = 2");
            case "source-revision" -> jdbc.update("UPDATE source SET location_revision = 2");
            case "context-revision" -> jdbc.update("UPDATE location_context SET revision = 2");
            case "unbound" -> jdbc.update("UPDATE source SET bound_location_context_id = NULL, binding_evidence_json = NULL");
            case "context-retired" -> jdbc.update("UPDATE location_context SET lifecycle_status = 'RETIRED'");
            case "context-unaccepted" -> jdbc.update("UPDATE location_context SET continuity_status = 'REVIEW_REQUIRED'");
            case "unresolved" -> jdbc.update("UPDATE file_entry SET location_identity_status = 'UNRESOLVED', location_context_id = NULL, location_path = NULL, location_key = NULL");
            case "no-content" -> jdbc.update("UPDATE file_entry SET current_content_id = NULL");
            case "no-membership" -> jdbc.update("DELETE FROM source_membership");
        }
        assertTrue(library.findItems(null, null).items().isEmpty());
        assertTrue(groups.findGroups(null, null, null).groups().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "analyzer", "version", "config-version", "config-hash", "pending", "failed", "missing", "unsupported"})
    void recognizedImageRemainsVisibleWithoutAvailableMetadata(String mutation) {
        fixture.image("jpeg", "image.jpg");
        switch (mutation) {
            case "type" -> jdbc.update("UPDATE analysis_record SET analysis_type = 'OTHER'");
            case "analyzer" -> jdbc.update("UPDATE analysis_record SET analyzer_id = 'old.imageio'");
            case "version" -> jdbc.update("UPDATE analysis_record SET analyzer_version = '0'");
            case "config-version" -> jdbc.update("UPDATE analysis_record SET configuration_version = 2");
            case "config-hash" -> jdbc.update("UPDATE analysis_record SET configuration_hash = 'other'");
            case "pending" -> jdbc.update("UPDATE analysis_record SET status = 'PENDING'");
            case "failed" -> jdbc.update("UPDATE analysis_record SET status = 'FAILED'");
            case "missing" -> jdbc.update("DELETE FROM analysis_record");
            case "unsupported" -> jdbc.update("UPDATE analysis_record SET result_json = ?", codec.write(new UnsupportedMediaMetadata(1)));
        }
        var item = library.findItems(null, null).items().getFirst();
        assertEquals("image.jpg", item.displayName());
        assertEquals("jpg", item.extensionKey());
        assertEquals(42, item.sizeBytes());
        assertNull(item.format());
        assertNull(item.encodedWidth());
        assertNull(item.encodedHeight());
        assertEquals(MediaLibraryItem.GenerationSupport.UNSUPPORTED, item.generationSupport());
        assertEquals(ThumbnailReference.missing(), item.thumbnail());
        assertEquals(item, groups.findGroups(null, null, null).groups().getFirst().representative());
    }

    @Test
    void heicWithoutMetadataAppearsInItemsAndExactGroupWithPhysicalCopyCount() {
        long contentId = catalog.insert(new ContentRecord(null, 42, 1)).id();
        var first = fixture.occurrence(contentId, "camera/first.HEIC");
        var second = fixture.occurrence(contentId, "copies/second.heic");
        var items = library.findItems(null, null).items();
        assertEquals(List.of(first.id(), second.id()), items.stream().map(MediaLibraryItem::fileEntryId).toList());
        assertEquals("heic", items.getFirst().extensionKey());
        assertNull(items.getFirst().format());
        assertEquals(MediaLibraryItem.GenerationSupport.UNSUPPORTED, items.getFirst().generationSupport());
        var group = groups.findGroups(null, null, null).groups().getFirst();
        assertEquals(2, group.currentItemCount());
        assertEquals(first.id(), group.representative().fileEntryId());
    }

    @Test
    void incompatibleCompletedVideoMetadataOnRecognizedImageFailsClosed() {
        fixture.image("png", "image.png");
        jdbc.update("UPDATE analysis_record SET result_json = ?", codec.write(new AvailableMediaMetadata(1,
                MediaKind.VIDEO, null, new VideoMediaMetadata(List.of("mov"), null, "h264", 600, 400, 1, null, 0))));
        assertThrows(IllegalStateException.class, () -> library.findItems(null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "version", "dimensions", "shape", "null"})
    void corruptCompatibleImageJsonIsRejectedByTheExistingCodec(String corruption) {
        fixture.image("png", "image.png");
        String json = switch (corruption) {
            case "broken" -> "{broken";
            case "version" -> "{\"version\":2,\"outcome\":\"AVAILABLE\",\"mediaKind\":\"IMAGE\",\"image\":{\"format\":\"png\",\"width\":600,\"height\":400}}";
            case "dimensions" -> "{\"version\":1,\"outcome\":\"AVAILABLE\",\"mediaKind\":\"IMAGE\",\"image\":{\"format\":\"png\",\"width\":0,\"height\":400}}";
            case "shape" -> "{\"version\":1,\"outcome\":\"AVAILABLE\",\"mediaKind\":\"IMAGE\"}";
            case "null" -> null;
            default -> throw new IllegalArgumentException(corruption);
        };
        jdbc.update("UPDATE analysis_record SET result_json = ?", json);
        assertThrows(InvalidMediaMetadataResultException.class, () -> library.findItems(null, null));
        assertThrows(InvalidMediaMetadataResultException.class, () -> groups.findGroups(null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "gif", "bmp", "tiff"})
    void generationSupportUsesDetectedFormatAndAllowsNullableExtension(String format) {
        fixture.image(format, "no-extension");
        var item = library.findItems(null, null).items().getFirst();
        assertNull(item.extensionKey());
        assertEquals(format.equals("jpeg") || format.equals("png") ? MediaLibraryItem.GenerationSupport.SUPPORTED
                : MediaLibraryItem.GenerationSupport.UNSUPPORTED, item.generationSupport());
        assertEquals(item, groups.findGroups(null, null, null).groups().getFirst().representative());
    }

    @Test
    void currentThumbnailProjectsPublishedWithoutAnyPhysicalCacheFile() {
        var entry = fixture.image("png", "image.png");
        var asset = assets.insert(fixture.thumbnail(entry, SmallThumbnailDefinition.definition()));
        var thumbnail = library.findItems(null, null).items().getFirst().thumbnail();
        assertEquals(ThumbnailReference.State.PUBLISHED, thumbnail.state());
        assertEquals(asset.assetKey(), thumbnail.assetKey());
        assertEquals("/api/previews/" + asset.assetKey(), thumbnail.url());
        assertEquals(120, thumbnail.width());
        assertEquals(80, thumbnail.height());
        assertEquals(thumbnail, groups.findGroups(null, null, null).groups().getFirst().representative().thumbnail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "revision", "size", "second", "nano", "generator", "generator-version", "config-version", "config-hash", "kind"})
    void staleOrIncompatiblePreviewMetadataDoesNotProjectAsCurrent(String mutation) {
        var entry = fixture.image("png", "image.png");
        var definition = SmallThumbnailDefinition.definition();
        var evidence = PreviewSourceEvidence.from(entry);
        PreviewKind kind = PreviewKind.SMALL_THUMBNAIL;
        switch (mutation) {
            case "content" -> evidence = new PreviewSourceEvidence(entry.id(), catalog.insert(new ContentRecord(null, 42, 1)).id(), 3, 42, 1700000000L, 123456789);
            case "revision" -> evidence = new PreviewSourceEvidence(entry.id(), entry.currentContentId(), 2, 42, 1700000000L, 123456789);
            case "size" -> evidence = new PreviewSourceEvidence(entry.id(), entry.currentContentId(), 3, 41, 1700000000L, 123456789);
            case "second" -> evidence = new PreviewSourceEvidence(entry.id(), entry.currentContentId(), 3, 42, 1700000001L, 123456789);
            case "nano" -> evidence = new PreviewSourceEvidence(entry.id(), entry.currentContentId(), 3, 42, 1700000000L, 123456788);
            case "generator" -> definition = new PreviewDefinition("other", "1", 1, definition.configurationHash(), definition.configurationJson());
            case "generator-version" -> definition = new PreviewDefinition(definition.generatorId(), "0", 1, definition.configurationHash(), definition.configurationJson());
            case "config-version" -> definition = new PreviewDefinition(definition.generatorId(), "1", 2, definition.configurationHash(), definition.configurationJson());
            case "config-hash" -> definition = new PreviewDefinition(definition.generatorId(), "1", 1, "other", definition.configurationJson());
            case "kind" -> kind = PreviewKind.MEDIUM_PREVIEW;
        }
        assets.insert(PreviewTestFixtures.asset(evidence, kind, definition, 6));
        assertEquals(ThumbnailReference.missing(), library.findItems(null, null).items().getFirst().thumbnail());
        assertEquals(ThumbnailReference.missing(),
                groups.findGroups(null, null, null).groups().getFirst().representative().thumbnail());
    }

    @Test
    void keysetPagesAcrossIneligibleGapsWithoutDuplicatingPhysicalItems() {
        var expected = new ArrayList<Long>();
        for (int index = 0; index < 9; index++) {
            var entry = fixture.image("jpeg", "image-" + index + ".jpg");
            if (index % 3 == 0) jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE file_entry_id = ?", entry.id());
            else expected.add(entry.id());
        }
        var actual = new ArrayList<Long>();
        Long cursor = null;
        do {
            var page = library.findItems(cursor, 2);
            actual.addAll(page.items().stream().map(MediaLibraryItem::fileEntryId).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(expected, actual);
        assertTrue(library.findItems(Long.MAX_VALUE, 200).items().isEmpty());
    }

    @Test
    void keysetPagesIncludeMetadataFreeHeicBetweenSupportedImages() {
        var first = fixture.image("jpeg", "first.jpg");
        var heic = fixture.occurrence(catalog.insert(new ContentRecord(null, 42, 1)).id(), "middle.HEIF");
        var last = fixture.image("png", "last.png");
        assertEquals(List.of(first.id(), heic.id()), library.findItems(null, 2).items().stream()
                .map(MediaLibraryItem::fileEntryId).toList());
        assertEquals(List.of(last.id()), library.findItems(heic.id(), 2).items().stream()
                .map(MediaLibraryItem::fileEntryId).toList());
    }

    @Test
    void defaultMaximumAndExactBoundaryPagesHaveCorrectNextCursors() {
        var entries = new ArrayList<FileEntry>();
        for (int index = 0; index < 201; index++) entries.add(fixture.image("png", "image-" + index + ".png"));
        var defaultPage = library.findItems(null, null);
        assertEquals(50, defaultPage.items().size());
        assertEquals(entries.get(49).id(), defaultPage.nextCursor());
        var maxPage = library.findItems(0L, 200);
        assertEquals(200, maxPage.items().size());
        assertEquals(entries.get(199).id(), maxPage.nextCursor());
        var finalPage = library.findItems(maxPage.nextCursor(), 1);
        assertEquals(entries.getLast().id(), finalPage.items().getFirst().fileEntryId());
        assertNull(finalPage.nextCursor());
        assertNull(library.findItems(entries.getLast().id(), 1).nextCursor());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 201, Integer.MAX_VALUE})
    void rejectsInvalidLimits(int limit) {
        assertThrows(IllegalArgumentException.class, () -> library.findItems(null, limit));
    }

    @Test
    void negativeCursorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> library.findItems(-1L, null));
    }

    @Test
    void queryPlanUsesExistingKeysetAndJoinIndexes() {
        fixture.image("png", "image.png");
        var details = jdbc.queryForList("EXPLAIN QUERY PLAN " + MediaLibraryRepository.PAGE_SQL,
                MediaLibraryRepository.pageParameters(0, 51)).stream().map(row -> row.get("detail").toString()).toList();
        assertTrue(details.stream().anyMatch(detail -> detail.contains("SEARCH entry USING INTEGER PRIMARY KEY (rowid>?")));
        assertFalse(details.stream().anyMatch(detail -> detail.contains("ORDER BY")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("idx_source_membership_file")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("sqlite_autoindex_analysis_record")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("sqlite_autoindex_preview_asset")));
    }
}
