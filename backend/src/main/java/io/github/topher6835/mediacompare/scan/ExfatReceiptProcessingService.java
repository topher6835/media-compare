package io.github.topher6835.mediacompare.scan;

import java.util.List;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ContentHashingResult;
import io.github.topher6835.mediacompare.analysis.ContentHashWriter;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentWriter;
import io.github.topher6835.mediacompare.catalog.ExfatOccurrenceRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB-only continuation beneath the original admitted windows. Never opens an original path. */
@Service
public class ExfatReceiptProcessingService {
    private final ExfatScanBundles bundles;
    private final ExfatOccurrenceRepository occurrences;
    private final ExfatReceiptAuthority receipts;
    private final ContentAssignmentWriter assignmentWriter;
    private final AnalysisRepository analyses;
    private final ContentHashWriter hashWriter;
    private final LocationContextRepository contexts;
    private final TransactionTemplate transactions;

    public ExfatReceiptProcessingService(ExfatScanBundles bundles, ExfatOccurrenceRepository occurrences,
            ExfatReceiptAuthority receipts, ContentAssignmentWriter assignmentWriter, AnalysisRepository analyses,
            ContentHashWriter hashWriter, LocationContextRepository contexts, PlatformTransactionManager manager) {
        this.bundles = bundles; this.occurrences = occurrences; this.receipts = receipts;
        this.assignmentWriter = assignmentWriter; this.analyses = analyses; this.hashWriter = hashWriter; this.contexts = contexts;
        transactions = new TransactionTemplate(manager);
    }

    public ContentAssignmentResult assign(long scanRunId) {
        long assigned = 0, skipped = 0, after = 0;
        var authorities = bundles.checkedAuthorities(scanRunId);
        try (var lease = bundles.retain(authorities)) {
            while (true) {
                lease.checkpoint();
                List<FileEntry> page = scanOccurrences(scanRunId, after);
                if (page.isEmpty()) break;
                for (var captured : page) {
                    after = captured.id();
                    boolean created;
                    try (var publication = bundles.lease(authorities)) {
                        created = transactions.execute(status -> {
                            contexts.reserveWrite(); lease.checkpoint();
                            boolean assignedNow = assignmentWriter.assignExfat(captured, scanRunId, System.currentTimeMillis());
                            lease.checkpoint(); return assignedNow;
                        });
                    }
                    lease.progress();
                    if (created) assigned++; else skipped++;
                }
            }
        }
        return new ContentAssignmentResult(scanRunId, assigned, skipped);
    }

    public ContentHashingResult hash(long scanRunId) {
        long hashed = 0, cached = 0, after = 0;
        var authorities = bundles.checkedAuthorities(scanRunId);
        try (var lease = bundles.retain(authorities)) {
            while (true) {
                lease.checkpoint();
                var page = scanOccurrences(scanRunId, after);
                if (page.isEmpty()) break;
                for (var captured : page) {
                    after = captured.id();
                    boolean created;
                    try (var publication = bundles.lease(authorities)) {
                        created = transactions.execute(status -> {
                            contexts.reserveWrite(); lease.checkpoint();
                            var receipt = receipts.reserveAndRequire(captured, scanRunId, ScanExecutionDefinition.CONTENT_HASHING);
                            receipts.requireContent(captured); // receipt and current occurrence are checked before cache lookup
                            var existing = analyses.findAnalysisRecordByCacheKey(captured.currentContentId(),
                                    Sha256AnalysisDefinition.ANALYSIS_TYPE, Sha256AnalysisDefinition.ANALYZER_ID,
                                    Sha256AnalysisDefinition.ANALYZER_VERSION, Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                                    Sha256AnalysisDefinition.CONFIGURATION_HASH);
                            if (existing.isPresent()) {
                                var analysis = existing.orElseThrow();
                                var hash = analyses.findContentHash(analysis.id()).orElseThrow();
                                if (!"COMPLETED".equals(analysis.status()) || !Sha256AnalysisDefinition.ALGORITHM.equals(hash.algorithm())
                                        || !receipt.sha256().equals(hash.digestHex())) throw integrity();
                                lease.checkpoint(); return false;
                            }
                            hashWriter.publishExfat(captured, scanRunId, System.currentTimeMillis());
                            lease.checkpoint(); return true;
                        });
                    }
                    lease.progress();
                    if (created) hashed++; else cached++;
                }
            }
        }
        return new ContentHashingResult(scanRunId, hashed, cached, 0, 0);
    }

    private List<FileEntry> scanOccurrences(long scanRunId, long afterId) {
        return occurrences.scanOccurrences(scanRunId, bundles.authorities(scanRunId).stream()
                .map(a -> a.scope().source().id()).toList(), afterId, 250);
    }
    private static IllegalStateException integrity() { return new IllegalStateException("exFAT receipt/content integrity contradiction"); }
}
