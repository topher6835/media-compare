package io.github.topher6835.mediacompare.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContentAssignmentWriter {

    private final CatalogRepository catalogRepository;
    private final CurrentMembershipAuthority authority;

    public ContentAssignmentWriter(CatalogRepository catalogRepository,
            CurrentMembershipAuthority authority) {
        this.catalogRepository = catalogRepository;
        this.authority = authority;
    }

    @Transactional
    public void assign(ContentAssignmentCandidate candidate, long createdAtMs) {
        authority.reserveAndRequire(candidate.sourceId(), candidate.sourceLocationRevision(),
                candidate.contextId(), candidate.contextRevision(), candidate.fileEntryId(),
                candidate.membershipId());
        ContentRecord contentRecord = catalogRepository.insert(new ContentRecord(
                null, candidate.sizeBytes(), createdAtMs));
        int updatedRows = catalogRepository.attachContentIfCurrent(candidate, contentRecord.id());
        if (updatedRows != 1) {
            throw new StaleContentAssignmentException(candidate.fileEntryId());
        }
    }
}
