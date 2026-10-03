package io.github.topher6835.mediacompare.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContentAssignmentWriter {

    private final CatalogRepository catalogRepository;
    private final CurrentMembershipAuthority authority;
    private io.github.topher6835.mediacompare.scan.ExfatReceiptAuthority receipts;
    private ExfatOccurrenceRepository occurrences;
    @org.springframework.beans.factory.annotation.Autowired
    void exfatReceipts(io.github.topher6835.mediacompare.scan.ExfatReceiptAuthority receipts, ExfatOccurrenceRepository occurrences) {
        this.receipts = receipts; this.occurrences = occurrences;
    }

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

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean assignExfat(FileEntry captured, long scanRunId, long createdAtMs) {
        receipts.reserveAndRequire(captured, scanRunId, "CONTENT_ASSIGNMENT");
        if (captured.currentContentId() != null) { receipts.requireContent(captured); return false; }
        var content = catalogRepository.insert(new ContentRecord(null, captured.sizeBytes(), createdAtMs));
        occurrences.attachContent(captured, content.id());
        return true;
    }
}
