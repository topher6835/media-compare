package io.github.topher6835.mediacompare.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import io.github.topher6835.mediacompare.matching.ExactHashRelationshipProjection;
import io.github.topher6835.mediacompare.matching.MediaRelationshipGroupService;
import io.github.topher6835.mediacompare.matching.MediaRelationshipSelection;

import org.junit.jupiter.api.Test;

class MediaLibraryGroupProjectionTests {
    @Test
    void allPhysicalItemsContributeOnceIncludingSingletonsRepeatedContentAndHiddenComponents() {
        var projection = new MediaLibraryGroupProjection(List.of(List.of(10L, 20L, 30L), List.of(40L, 50L)));
        var representative = item(7, 20);
        projection.add(List.of(representative, item(8, 99), item(9, 100)));
        projection.add(List.of(item(12, 30), item(14, 99)));
        var page = projection.page(0, 50);
        assertEquals(List.of(new MediaLibraryGroupSummary(10, representative, 2),
                new MediaLibraryGroupSummary(99, item(8, 99), 2),
                new MediaLibraryGroupSummary(100, item(9, 100), 1)), page.groups());
        assertEquals(5, page.groups().stream().mapToLong(MediaLibraryGroupSummary::currentItemCount).sum());
        assertNull(page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.groups().clear());
    }

    @Test
    void choosesLowestPhysicalIdAndFullComponentKeyRegardlessOfInputOrdering() {
        var projection = new MediaLibraryGroupProjection(List.of(List.of(30L, 10L, 20L)));
        projection.add(List.of(item(9, 30), item(2, 20), item(1, 99)));
        assertEquals(List.of(new MediaLibraryGroupSummary(99, item(1, 99), 1),
                new MediaLibraryGroupSummary(10, item(2, 20), 2)), projection.page(0, 50).groups());
    }

    @Test
    void cursorIsAppliedAfterAllMembersAndCountsWithOneGroupLookahead() {
        var projection = new MediaLibraryGroupProjection(List.of(List.of(10L, 20L)));
        projection.add(List.of(item(1, 10), item(2, 99), item(3, 100), item(500, 20)));
        assertEquals(2, projection.page(0, 1).groups().getFirst().currentItemCount());
        assertEquals(1L, projection.page(0, 1).nextCursor());
        assertEquals(List.of(new MediaLibraryGroupSummary(99, item(2, 99), 1)), projection.page(1, 1).groups());
        assertEquals(2L, projection.page(1, 1).nextCursor());
        assertEquals(List.of(new MediaLibraryGroupSummary(100, item(3, 100), 1)), projection.page(2, 1).groups());
        assertNull(projection.page(2, 1).nextCursor());
        assertEquals(List.of(), projection.page(3, 1).groups());
    }

    @Test
    void serviceScansBoundedKeysetBatchesFromZeroEvenWithAnExternalCursor() {
        var relationships = new RecordingRelationships();
        var library = new RecordingLibrary(1001);
        var service = new MediaLibraryGroupService(relationships, library);
        var page = service.findGroups(null, 999L, 1);
        assertEquals(List.of(new MediaLibraryGroupSummary(1000, item(1000, 1000), 1)), page.groups());
        // FileEntry 1001 shares ContentRecord 1, whose representative is before the cursor.
        assertNull(page.nextCursor());
        assertEquals(List.of(0L, 500L, 1000L), library.cursors);
        assertEquals(List.of(500, 500, 500), library.limits);
        assertEquals(List.of(new MediaRelationshipSelection(List.of(ExactHashRelationshipProjection.DEFINITION))),
                relationships.selections);
    }

    @Test
    void fullBatchBoundaryMakesOnlyOneFinalEmptyScanAndNoPerGroupQueries() {
        var relationships = new RecordingRelationships();
        var library = new RecordingLibrary(1000);
        var page = new MediaLibraryGroupService(relationships, library).findGroups(null, null, null);
        assertEquals(50, page.groups().size());
        assertEquals(50L, page.nextCursor());
        assertEquals(List.of(0L, 500L, 1000L), library.cursors);
        assertEquals(List.of(500, 500, 500), library.limits);
        assertEquals(1, relationships.selections.size());
    }

    private static MediaLibraryItem item(long fileId, long contentId) {
        return new MediaLibraryItem(fileId, contentId, 1, "Source", "image.png", "image.png", "png",
                42, "png", 600, 400, 1, MediaLibraryItem.GenerationSupport.SUPPORTED, ThumbnailReference.missing());
    }

    private static final class RecordingLibrary extends MediaLibraryRepository {
        private final int count;
        private final List<Long> cursors = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();

        RecordingLibrary(int count) {
            super(null, null);
            this.count = count;
        }

        @Override
        public List<MediaLibraryItem> findPage(long afterFileEntryId, int limit) {
            cursors.add(afterFileEntryId);
            limits.add(limit);
            List<MediaLibraryItem> items = new ArrayList<>();
            for (long id = afterFileEntryId + 1; id <= Math.min(count, afterFileEntryId + limit); id++) {
                items.add(item(id, id == 1001 ? 1 : id));
            }
            return items;
        }
    }

    private static final class RecordingRelationships extends MediaRelationshipGroupService {
        private final List<MediaRelationshipSelection> selections = new ArrayList<>();

        RecordingRelationships() {
            super(null, null);
        }

        @Override
        public List<List<Long>> group(MediaRelationshipSelection selection) {
            selections.add(selection);
            return List.of();
        }
    }
}
