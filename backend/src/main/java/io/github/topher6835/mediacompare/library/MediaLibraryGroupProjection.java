package io.github.topher6835.mediacompare.library;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure incremental projection; retains one representative/count per group, not all physical items. */
final class MediaLibraryGroupProjection {
    private final Map<Long, Long> componentKeys = new HashMap<>();
    private final Map<Long, MediaLibraryGroupSummary> groups = new HashMap<>();

    MediaLibraryGroupProjection(List<List<Long>> components) {
        for (List<Long> component : components) {
            long key = component.stream().mapToLong(Long::longValue).min().orElseThrow();
            for (long contentId : component) {
                componentKeys.put(contentId, key);
            }
        }
    }

    /** Call once per physical current item, in bounded batches. */
    void add(Collection<MediaLibraryItem> items) {
        for (MediaLibraryItem item : items) {
            long key = componentKeys.getOrDefault(item.contentRecordId(), item.contentRecordId());
            MediaLibraryGroupSummary existing = groups.get(key);
            if (existing == null) {
                groups.put(key, new MediaLibraryGroupSummary(key, item, 1));
            } else {
                MediaLibraryItem representative = item.fileEntryId() < existing.representative().fileEntryId()
                        ? item : existing.representative();
                groups.put(key, new MediaLibraryGroupSummary(key, representative, existing.currentItemCount() + 1));
            }
        }
    }

    /** Apply the external cursor only after all members have contributed to representatives/counts. */
    MediaLibraryGroupPage page(long afterRepresentativeFileEntryId, int limit) {
        List<MediaLibraryGroupSummary> rows = groups.values().stream()
                .filter(group -> group.representative().fileEntryId() > afterRepresentativeFileEntryId)
                .sorted(Comparator.comparingLong(group -> group.representative().fileEntryId()))
                .limit(limit + 1L).toList();
        boolean hasNext = rows.size() > limit;
        List<MediaLibraryGroupSummary> page = hasNext ? rows.subList(0, limit) : rows;
        return new MediaLibraryGroupPage(page, hasNext ? page.getLast().representative().fileEntryId() : null);
    }
}
