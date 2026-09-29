package io.github.topher6835.mediacompare.matching;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

/** Safe persistence-to-grouping boundary; callers explicitly supply accepted definitions. */
@Service
public class MediaRelationshipGroupService {
    private final MediaRelationshipRepository repository;
    private final MediaRelationshipGrouping grouping = new MediaRelationshipGrouping();

    public MediaRelationshipGroupService(MediaRelationshipRepository repository) {
        this.repository = repository;
    }

    public List<List<Long>> group(MediaRelationshipSelection selection) {
        Objects.requireNonNull(selection, "selection");
        return grouping.group(repository.findBySelection(selection), selection.enabledTypes());
    }
}
