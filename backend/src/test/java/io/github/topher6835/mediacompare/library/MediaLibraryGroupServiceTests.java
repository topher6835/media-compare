package io.github.topher6835.mediacompare.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.preview.PreviewAssetRepository;
import io.github.topher6835.mediacompare.preview.SmallThumbnailDefinition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:library-groups-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaLibraryGroupServiceTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired AnalysisRepository analysis;
    @Autowired MediaMetadataResultCodec codec;
    @Autowired MediaLibraryGroupService groups;
    @Autowired MediaLibraryService items;
    @Autowired PreviewAssetRepository assets;
    MediaLibraryTestFixtures fixture;

    @BeforeEach
    void seed() {
        MediaLibraryTestFixtures.clear(jdbc);
        fixture = new MediaLibraryTestFixtures(jdbc, catalog, analysis, codec);
    }

    @Test
    void unrelatedImagesAllAppearExactlyOnceAsSingletonGroups() {
        fixture.image("jpeg", "a.bin");
        fixture.image("png", "b.png");
        fixture.image("gif", "c.gif");
        List<MediaLibraryItem> current = items.findItems(null, null).items();
        assertEquals(current.stream().map(item -> new MediaLibraryGroupSummary(item.contentRecordId(), item, 1))
                .toList(), groups.findGroups(null, null, null).groups());
        assertNull(groups.findGroups(null, null, null).nextCursor());
    }

    @Test
    void threeEqualCurrentImagesUseLowestPhysicalRepresentativeWithItsUnchangedThumbnail() {
        var a = fixture.image("png", "a.png");
        var b = fixture.image("jpeg", "b.jpg");
        var c = fixture.image("gif", "c.gif");
        for (var entry : List.of(c, b, a)) fixture.hash(entry.currentContentId(), digest(10));
        assets.insert(fixture.thumbnail(a, SmallThumbnailDefinition.definition()));
        MediaLibraryItem representative = items.findItems(null, null).items().getFirst();
        assertEquals(List.of(new MediaLibraryGroupSummary(a.currentContentId(), representative, 3)),
                groups.findGroups(null, null, null).groups());
    }

    @Test
    void hiddenSmallestContentConnectsTwoImagesWithoutInflatingTheirCurrentCount() {
        long hidden = hiddenContent(10);
        var a = fixture.image("png", "a.png");
        var b = fixture.image("png", "b.png");
        fixture.hash(a.currentContentId(), digest(10));
        fixture.hash(b.currentContentId(), digest(10));
        MediaLibraryItem representative = items.findItems(null, null).items().getFirst();
        assertEquals(List.of(new MediaLibraryGroupSummary(hidden, representative, 2)),
                groups.findGroups(null, null, null).groups());
    }

    @Test
    void graphOnlyAndMissingMembersDoNotInflateOneCurrentItemAndZeroCurrentComponentIsOmitted() {
        long hidden = hiddenContent(10);
        hiddenContent(10);
        var missing = fixture.image("png", "missing.png");
        var current = fixture.image("png", "current.png");
        fixture.hash(missing.currentContentId(), digest(10));
        fixture.hash(current.currentContentId(), digest(10));
        jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE file_entry_id = ?", missing.id());
        hiddenContent(20);
        hiddenContent(20);
        MediaLibraryItem representative = items.findItems(null, null).items().getFirst();
        assertEquals(current.id(), representative.fileEntryId());
        assertEquals(List.of(new MediaLibraryGroupSummary(hidden, representative, 1)),
                groups.findGroups(null, null, null).groups());
    }

    @Test
    void sharedContentGroupsPhysicalOccurrencesWithoutHashesOrEdgesAndOverlappingSourcesCountOnce() {
        var a = fixture.image("png", "a.png");
        fixture.occurrence(a.currentContentId(), "b.png");
        fixture.membership(fixture.source("secondary"), a, "other/a.png");
        MediaLibraryItem representative = items.findItems(null, null).items().getFirst();
        assertEquals(2, representative.sourceCount());
        assertEquals(List.of(new MediaLibraryGroupSummary(a.currentContentId(), representative, 2)),
                groups.findGroups(null, null, null).groups());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM media_relationship", Long.class));
    }

    @Test
    void representativeOrderingAndInterleavedMembersRemainCorrectAcrossGroupCursorPages() {
        long hidden = hiddenContent(20);
        var a = fixture.image("png", "a.png");
        var b = fixture.image("png", "b.png");
        var c = fixture.image("png", "c.png");
        var d = fixture.image("png", "d.png");
        var e = fixture.image("png", "e.png");
        fixture.hash(a.currentContentId(), digest(10));
        fixture.hash(c.currentContentId(), digest(10));
        fixture.hash(b.currentContentId(), digest(20));
        fixture.hash(e.currentContentId(), digest(20));
        var current = items.findItems(null, null).items();
        var expected = List.of(new MediaLibraryGroupSummary(a.currentContentId(), current.get(0), 2),
                new MediaLibraryGroupSummary(hidden, current.get(1), 2),
                new MediaLibraryGroupSummary(d.currentContentId(), current.get(3), 1));
        var actual = new ArrayList<MediaLibraryGroupSummary>();
        Long cursor = null;
        do {
            var page = groups.findGroups(null, cursor, 1);
            actual.addAll(page.groups());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(expected, actual);
        assertEquals(List.of(), groups.findGroups(null, d.id(), 1).groups());
        assertEquals(List.of(), groups.findGroups(null, Long.MAX_VALUE, 200).groups());
    }

    @Test
    void defaultMaximumAndExactBoundaryPagesUseOneLookaheadGroup() {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 201; i++) ids.add(fixture.image("png", "image-" + i + ".png").id());
        var defaults = groups.findGroups(null, null, null);
        assertEquals(50, defaults.groups().size());
        assertEquals(ids.get(49), defaults.nextCursor());
        var maximum = groups.findGroups(null, 0L, 200);
        assertEquals(200, maximum.groups().size());
        assertEquals(ids.get(199), maximum.nextCursor());
        var last = groups.findGroups(null, maximum.nextCursor(), 1);
        assertEquals(ids.getLast(), last.groups().getFirst().representative().fileEntryId());
        assertNull(last.nextCursor());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 201, Integer.MAX_VALUE})
    void invalidLimitsFailBeforeProjection(int limit) {
        assertThrows(IllegalArgumentException.class, () -> groups.findGroups(null, null, limit));
    }

    @Test
    void negativeCursorIsAnInputError() {
        assertThrows(IllegalArgumentException.class, () -> groups.findGroups(null, -1L, null));
    }

    private long hiddenContent(long hashValue) {
        long id = catalog.insert(new ContentRecord(null, 42, 1)).id();
        fixture.hash(id, digest(hashValue));
        return id;
    }

    private static String digest(long value) {
        return "%064x".formatted(value);
    }
}
