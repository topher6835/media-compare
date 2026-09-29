package io.github.topher6835.mediacompare.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Safe projection/persistence-to-grouping boundary; callers explicitly supply accepted definitions. */
@Service
public class MediaRelationshipGroupService {
    private final MediaRelationshipRepository repository;
    private final ExactHashRelationshipProjection exactProjection;
    private final MediaRelationshipGrouping grouping = new MediaRelationshipGrouping();

    public MediaRelationshipGroupService(
            MediaRelationshipRepository repository, ExactHashRelationshipProjection exactProjection) {
        this.repository = repository;
        this.exactProjection = exactProjection;
    }

    @Transactional(readOnly = true)
    public List<List<Long>> group(MediaRelationshipSelection selection) {
        Objects.requireNonNull(selection, "selection");
        List<MediaRelationshipEdge> edges = new ArrayList<>();
        if (selection.definitions().contains(ExactHashRelationshipProjection.DEFINITION)) {
            edges.addAll(exactProjection.project());
        }
        MediaRelationshipSelection persistentSelection = new MediaRelationshipSelection(
                selection.definitions().stream()
                        .filter(definition -> !ExactHashRelationshipProjection.DEFINITION.equals(definition))
                        .toList());
        if (!persistentSelection.definitions().isEmpty()) {
            edges.addAll(repository.findBySelection(persistentSelection));
        }
        return grouping.group(edges, selection.enabledTypes());
    }
}
