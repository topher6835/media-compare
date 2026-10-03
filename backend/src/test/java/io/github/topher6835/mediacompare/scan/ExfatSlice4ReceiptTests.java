package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.job.JobRepository;

class ExfatSlice4ReceiptTests extends ExfatSlice4TestSupport {
    private ScanExecutionDetails throughReconciliation() {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "protected discovery bytes");
        var accepted = admit(id); reconcile(accepted, discover(accepted)); return accepted;
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "malformed", "token", "path", "file-revision", "context", "source",
            "window", "bundle", "scan", "job", "generation", "membership", "membership-route", "period"})
    void assignmentRejectsMissingMalformedMismatchedOrStaleReceipt(String mismatch) {
        var accepted = throughReconciliation(); var file = onlyFile();
        var receipt = OccurrenceProfileValidation.requireExfat(file);
        switch (mismatch) {
            case "missing" -> jdbc.update("UPDATE file_entry SET occurrence_token=NULL, observation_evidence_json=NULL");
            case "malformed" -> jdbc.update("UPDATE file_entry SET observation_evidence_json='{}'");
            case "token" -> jdbc.update("UPDATE file_entry SET occurrence_token=?", UUID.randomUUID().toString());
            case "path" -> jdbc.update("UPDATE file_entry SET location_path=?, location_key=?", lp(ROOT.append("wrong.jpg")), key(ROOT.append("wrong.jpg")));
            case "file-revision" -> jdbc.update("UPDATE file_entry SET observation_revision=1");
            case "context" -> jdbc.update("UPDATE location_context SET revision=2");
            case "source" -> jdbc.update("UPDATE source SET location_revision=2");
            case "window" -> replaceReceipt(file, receipt.sources().getFirst().windowUuid(), UUID.randomUUID().toString());
            case "bundle" -> replaceReceipt(file, receipt.bundleUuid(), UUID.randomUUID().toString());
            case "scan" -> replaceReceipt(file, "\"scanRunId\":" + receipt.scanRunId(), "\"scanRunId\":999");
            case "job" -> replaceReceipt(file, "\"scanJobId\":" + receipt.scanJobId(), "\"scanJobId\":999");
            case "generation" -> jdbc.update("UPDATE scan_run_source SET traversal_generation=2, completed_generation=2");
            case "membership" -> jdbc.update("UPDATE source_membership SET membership_revision=1");
            case "membership-route" -> jdbc.update("UPDATE source_membership SET relative_path='other.jpg', path_key='other.jpg'");
            case "period" -> jdbc.update("UPDATE source_binding_period SET bound_at_ms=11");
        }
        assertThrows(RuntimeException.class, () -> assignment(accepted));
        assertEquals(0, count("content_record")); assertEquals(1, access.opens);
    }

    @Test void overlappingReceiptAssignmentIsOnceAndIdempotentForSameOccurrence() {
        long parent = source(ROOT), child = source(ROOT.append("Nested"));
        access.add("C:\\Photos\\Nested\\photo.jpg", "same"); var accepted = admit(parent, child);
        reconcile(accepted, discover(accepted)); startStage(accepted, "CONTENT_ASSIGNMENT");
        var processing = app.getBean(ExfatReceiptProcessingService.class);
        assertEquals(1, processing.assign(accepted.job().scanRunId()).assignedCount());
        assertEquals(1, processing.assign(accepted.job().scanRunId()).skippedCount());
        assertEquals(1, count("content_record")); assertEquals(1, access.opens);
    }

    @Test void hashingPublishesReceiptDigestAndReusesOnlyMatchingCompletedAnalysis() {
        var accepted = throughReconciliation(); assignment(accepted); startStage(accepted, "CONTENT_HASHING");
        var processing = app.getBean(ExfatReceiptProcessingService.class);
        assertEquals(1, processing.hash(accepted.job().scanRunId()).hashedCount());
        access.files.clear(); access.failRead = true; // path is now unavailable; DB-only continuation still succeeds
        assertEquals(1, processing.hash(accepted.job().scanRunId()).cachedCount());
        assertEquals(1, count("content_hash")); assertEquals(1, access.opens);
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "window", "membership", "size", "digest", "release"})
    void hashCacheCannotBypassReceiptAuthorityOrDigestAndSizeIntegrity(String mismatch) {
        var accepted = throughReconciliation(); assignment(accepted); startStage(accepted, "CONTENT_HASHING");
        var processing = app.getBean(ExfatReceiptProcessingService.class); processing.hash(accepted.job().scanRunId());
        var file = onlyFile(); var receipt = OccurrenceProfileValidation.requireExfat(file);
        switch (mismatch) {
            case "malformed" -> jdbc.update("UPDATE file_entry SET observation_evidence_json='{}'");
            case "window" -> replaceReceipt(file, receipt.sources().getFirst().windowUuid(), UUID.randomUUID().toString());
            case "membership" -> jdbc.update("UPDATE source_membership SET membership_revision=1");
            case "size" -> jdbc.update("UPDATE content_record SET size_bytes=size_bytes+1");
            case "digest" -> jdbc.update("UPDATE content_hash SET digest_hex=?", "f".repeat(64));
            case "release" -> { registry.release(windows.get(1L)); prepare(1); }
        }
        assertThrows(RuntimeException.class, () -> processing.hash(accepted.job().scanRunId()));
        assertEquals(1, count("content_hash")); assertEquals(1, access.opens);
    }

    @Test void runtimeRecoveryPreservesCommittedReceiptAndContentButCannotContinueUnderNewWindow() {
        var accepted = throughReconciliation(); assignment(accepted); stop(accepted);
        var file = onlyFile(); assertNotNull(file.currentContentId()); assertEquals(0, count("content_hash"));
        prepare(1); assertThrows(RuntimeException.class, () -> hashing(accepted));
        assertEquals(file, onlyFile()); assertTrue(bundles.authorities(accepted.job().scanRunId()).isEmpty());
        assertEquals("FAILED", app.getBean(JobRepository.class).findJobById(accepted.job().id()).orElseThrow().status());
    }

    @Test void actualCatalogRestartFailsInterruptedScanAndReceiptCannotRecreateRuntimeAuthority() {
        var accepted = throughReconciliation(); assignment(accepted); var file = onlyFile();
        app.close(); open();
        assertEquals(file, onlyFile()); assertEquals(1, count("content_record")); assertEquals(0, count("content_hash"));
        assertEquals("FAILED", app.getBean(JobRepository.class).findJobById(accepted.job().id()).orElseThrow().status());
        assertFalse(registry.project(scopes.get(1L)).liveAuthorityAvailable()); prepare(1);
        assertThrows(RuntimeException.class, () -> app.getBean(ExfatReceiptProcessingService.class).hash(accepted.job().scanRunId()));
    }

    private void startStage(ScanExecutionDetails accepted, String stage) {
        var jobs = app.getBean(JobRepository.class);
        try (var lease = bundles.lease(bundles.authorities(accepted.job().scanRunId()))) {
            app.getBean(Version2ExecutionState.class).startStage(jobs.findJobById(accepted.job().id()).orElseThrow(),
                    jobs.findJobStageByJobIdAndType(accepted.job().id(), stage).orElseThrow(), System.currentTimeMillis());
        }
    }
    private void replaceReceipt(FileEntry file, String from, String to) {
        assertTrue(file.observationEvidenceJson().contains(from), "Receipt mutation must change an actual field");
        jdbc.update("UPDATE file_entry SET observation_evidence_json=? WHERE id=?", file.observationEvidenceJson().replace(from, to), file.id());
    }
}
