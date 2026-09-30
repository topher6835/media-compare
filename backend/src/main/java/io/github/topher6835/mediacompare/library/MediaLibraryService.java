package io.github.topher6835.mediacompare.library;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class MediaLibraryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private final MediaLibraryRepository repository;

    public MediaLibraryService(MediaLibraryRepository repository) {
        this.repository = repository;
    }

    public MediaLibraryPage findItems(Long afterFileEntryId, Integer requestedLimit) {
        long cursor = afterFileEntryId == null ? 0 : afterFileEntryId;
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (cursor < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Invalid media library cursor or limit");
        }
        List<MediaLibraryItem> rows = repository.findPage(cursor, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<MediaLibraryItem> items = hasNext ? rows.subList(0, limit) : rows;
        return new MediaLibraryPage(items, hasNext ? items.getLast().fileEntryId() : null);
    }

    public Optional<MediaLibraryItem> findItem(long fileEntryId) {
        return repository.findById(fileEntryId);
    }
}
