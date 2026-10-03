package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.catalog.CurrentMembershipAuthority;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContentHashWriter {

    private static final String COMPLETED_STATUS = "COMPLETED";

    private final CatalogRepository catalogRepository;
    private final AnalysisRepository analysisRepository;
    private final CurrentMembershipAuthority authority;
    private io.github.topher6835.mediacompare.scan.ExfatReceiptAuthority receipts;
    @org.springframework.beans.factory.annotation.Autowired
    void exfatReceipts(io.github.topher6835.mediacompare.scan.ExfatReceiptAuthority receipts) { this.receipts = receipts; }

    public ContentHashWriter(CatalogRepository catalogRepository, AnalysisRepository analysisRepository,
            CurrentMembershipAuthority authority) {
        this.catalogRepository = catalogRepository;
        this.analysisRepository = analysisRepository;
        this.authority = authority;
    }

    @Transactional
    public void publish(ContentHashCandidate candidate, String digestHex,
            long startedAtMs, long finishedAtMs) {
        if (!Sha256AnalysisDefinition.isValidDigest(digestHex)) {
            throw new IllegalArgumentException("SHA-256 digest must be lowercase 64-character hexadecimal");
        }
        authority.reserveAndRequire(candidate.sourceId(), candidate.sourceLocationRevision(),
                candidate.contextId(), candidate.contextRevision(), candidate.fileEntryId(),
                candidate.membershipId());
        if (catalogRepository.verifyContentHashCandidate(candidate) != 1) {
            throw new StaleContentHashException(candidate.fileEntryId(), "catalog evidence changed");
        }

        insertHash(candidate.contentRecordId(), digestHex, startedAtMs, finishedAtMs);
    }

    /** Receipt processor has already guarded the occurrence, memberships and exact bundle under the writer. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void publishExfat(io.github.topher6835.mediacompare.catalog.FileEntry captured,
            long scanRunId, long finishedAtMs) {
        var receipt = receipts.reserveAndRequire(captured, scanRunId, "CONTENT_HASHING");
        receipts.requireContent(captured);
        insertHash(captured.currentContentId(), receipt.sha256(), receipt.observationStartedAtMs(), finishedAtMs);
    }

    private void insertHash(long contentRecordId, String digestHex, long startedAtMs, long finishedAtMs) {
        AnalysisRecord analysisRecord = analysisRepository.insert(new AnalysisRecord(
                null,
                contentRecordId,
                Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON,
                null,
                COMPLETED_STATUS,
                1,
                startedAtMs,
                startedAtMs,
                finishedAtMs,
                null));
        analysisRepository.insert(new ContentHash(
                analysisRecord.id(), Sha256AnalysisDefinition.ALGORITHM, digestHex));
    }
}
