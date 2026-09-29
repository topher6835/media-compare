package io.github.topher6835.mediacompare.library;

import java.util.List;

import io.github.topher6835.mediacompare.matching.MediaRelationshipGroupService;
import io.github.topher6835.mediacompare.matching.MediaRelationshipType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaLibraryGroupService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int SCAN_BATCH_SIZE = 500;

    private final MediaRelationshipGroupService relationships;
    private final MediaLibraryRepository library;

    public MediaLibraryGroupService(MediaRelationshipGroupService relationships, MediaLibraryRepository library) {
        this.relationships = relationships;
        this.library = library;
    }

    /** Full graph first, then current physical items, all under one database read snapshot. */
    @Transactional(readOnly = true)
    public MediaLibraryGroupPage findGroups(List<MediaRelationshipType> requestedTypes,
            Long afterRepresentativeFileEntryId, Integer requestedLimit) {
        long cursor = afterRepresentativeFileEntryId == null ? 0 : afterRepresentativeFileEntryId;
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (cursor < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid media library group cursor or limit");
        }
        var selection = MediaLibraryRelationshipPolicy.resolve(requestedTypes);
        var projection = new MediaLibraryGroupProjection(relationships.group(selection));
        long afterFileEntryId = 0;
        while (true) {
            List<MediaLibraryItem> items = library.findPage(afterFileEntryId, SCAN_BATCH_SIZE);
            projection.add(items);
            if (items.size() < SCAN_BATCH_SIZE) {
                break;
            }
            afterFileEntryId = items.getLast().fileEntryId();
        }
        return projection.page(cursor, limit);
    }
}
