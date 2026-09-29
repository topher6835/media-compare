package io.github.topher6835.mediacompare.matching;

import java.util.List;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only graph adapter over authoritative current SHA-256 artifacts. */
@Service
public class ExactHashRelationshipProjection {

    // Identifies the projection mechanism; underlying hash authority still uses its analysis definition.
    public static final MediaRelationshipDefinition DEFINITION = new MediaRelationshipDefinition(
            MediaRelationshipType.EXACT, "builtin.sha256.exact-projection", "1", 1,
            Sha256AnalysisDefinition.CONFIGURATION_HASH, Sha256AnalysisDefinition.CONFIGURATION_JSON);

    private final ExactDuplicateRepository repository;

    public ExactHashRelationshipProjection(ExactDuplicateRepository repository) {
        this.repository = repository;
    }

    /** Validation and edge selection share a read snapshot; no artifacts are published. */
    @Transactional(readOnly = true)
    public List<ExactHashRelationshipEdge> project() {
        repository.validateIntegrity();
        return List.copyOf(repository.findExactRelationshipEdges());
    }
}
