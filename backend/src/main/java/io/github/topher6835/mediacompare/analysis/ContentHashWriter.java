package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ContentHashWriter {

    private static final String COMPLETED_STATUS = "COMPLETED";

    private final CatalogRepository catalogRepository;
    private final AnalysisRepository analysisRepository;

    public ContentHashWriter(CatalogRepository catalogRepository, AnalysisRepository analysisRepository) {
        this.catalogRepository = catalogRepository;
        this.analysisRepository = analysisRepository;
    }

    @Transactional
    public void publish(ContentHashCandidate candidate, String digestHex,
            long startedAtMs, long finishedAtMs) {
        if (!Sha256AnalysisDefinition.isValidDigest(digestHex)) {
            throw new IllegalArgumentException("SHA-256 digest must be lowercase 64-character hexadecimal");
        }
        if (catalogRepository.verifyContentHashCandidate(candidate) != 1) {
            throw new StaleContentHashException(candidate.fileEntryId(), "catalog evidence changed");
        }

        AnalysisRecord analysisRecord = analysisRepository.insert(new AnalysisRecord(
                null,
                candidate.contentRecordId(),
                Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON,
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
