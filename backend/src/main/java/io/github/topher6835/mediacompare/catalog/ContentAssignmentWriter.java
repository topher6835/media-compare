package io.github.topher6835.mediacompare.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContentAssignmentWriter {

    private final CatalogRepository catalogRepository;

    public ContentAssignmentWriter(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @Transactional
    public void assign(ContentAssignmentCandidate candidate, long createdAtMs) {
        ContentRecord contentRecord = catalogRepository.insert(new ContentRecord(
                null, candidate.sizeBytes(), createdAtMs));
        int updatedRows = catalogRepository.attachContentIfCurrent(
                candidate.fileEntryId(), candidate.observationRevision(), candidate.sizeBytes(),
                contentRecord.id());
        if (updatedRows != 1) {
            throw new StaleContentAssignmentException(candidate.fileEntryId());
        }
    }
}
