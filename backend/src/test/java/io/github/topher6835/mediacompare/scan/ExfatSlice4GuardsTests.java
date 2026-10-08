package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.job.JobRepository;

class ExfatSlice4GuardsTests extends ExfatSlice4TestSupport {
    @Test void missingPreparedWindowRejectsEntireAdmissionBeforeDurableWork() {
        long a = source(ROOT), b = source(ROOT.append("Nested")); registry.release(windows.get(b));
        assertThrows(RuntimeException.class, () -> admit(a, b));
        assertEquals(0, count("scan_run")); assertEquals(0, count("job")); assertEquals(0, count("file_entry"));
        assertTrue(registry.project(scopes.get(a)).liveAuthorityAvailable());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void staleWindowOrMixedRuntimeCaptureCannotAssociateAnySource(boolean foreignRuntime) {
        long a = source(ROOT), b = source(ROOT.append("Nested"));
        ExfatAuthorityWindowRegistry foreign = shortDrainRegistry(app.getBean(CatalogOwnership.class));
        try {
            ExfatAuthorityWindowRegistry.WindowId wrong;
            if (foreignRuntime) {
                var root = new Root(ROOT.append("Nested"));
                try (var attempt = foreign.begin(b, root.directoryCount(), CONTEXT)) {
                    wrong = foreign.installing(attempt, () -> foreign.install(attempt, scopes.get(b), root));
                }
            } else wrong = new ExfatAuthorityWindowRegistry.WindowId(registry.runtimeId(), b, UUID.randomUUID().toString());
            var captured = List.of(new ExfatScanBundles.Prepared(scopes.get(a), windows.get(a)), new ExfatScanBundles.Prepared(scopes.get(b), wrong));
            assertThrows(RuntimeException.class, () -> bundles.admitCaptured(captured, prepared -> {
                var request = app.getBean(ScanRunService.class).create(List.of(a, b), UUID.randomUUID().toString());
                return execution.create(request.scanRun().id(), prepared);
            }));
            assertEquals(0, count("scan_run")); assertTrue(registry.project(scopes.get(a)).liveAuthorityAvailable());
        } finally { foreign.destroy(); }
    }
    @Test void associationFailureAfterJobCreationRollsBackAdmissionWithoutPartialAssociation() {
        long a = source(ROOT), b = source(ROOT.append("Nested"));
        assertThrows(RuntimeException.class, () -> bundles.admit(List.of(a, b), authorityRequests(a, b), prepared -> {
            var request = app.getBean(ScanRunService.class).create(List.of(a, b));
            var accepted = execution.create(request.scanRun().id(), prepared);
            registry.release(windows.get(b)); return accepted;
        }));
        assertEquals(0, count("scan_run")); assertEquals(0, count("job"));
        assertTrue(registry.project(scopes.get(a)).liveAuthorityAvailable());
        var accepted = admit(a); assertEquals(1, bundles.authorities(accepted.job().scanRunId()).size()); stop(accepted);
    }
    @Test void failedCommitCancelsAllTransientAssociationsAndRollsBackJobAndRequest() {
        long a = source(ROOT), b = source(ROOT.append("Nested"));
        assertThrows(RuntimeException.class, () -> bundles.admit(List.of(a, b), authorityRequests(a, b), prepared -> {
            var request = app.getBean(ScanRunService.class).create(List.of(a, b));
            var accepted = execution.create(request.scanRun().id(), prepared);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("Admission commit rejected"); }
            });
            return accepted;
        }));
        assertEquals(0, count("scan_run")); assertEquals(0, count("job"));
        assertFalse(registry.project(scopes.get(a)).liveAuthorityAvailable()); assertFalse(registry.project(scopes.get(b)).liveAuthorityAvailable());
    }
    @Test void requestKeyReplayCannotAttachLaterPreparedWindow() {
        long id = source(ROOT); String key = UUID.randomUUID().toString();
        var first = app.getBean(IndexingRunAcceptance.class).accept(key, List.of(id), authorityRequests(id)); stop(first); var fresh = prepare(id);
        var replay = app.getBean(IndexingRunService.class).start(key, List.of(id));
        assertFalse(replay.created()); assertEquals("FAILED", replay.run().job().status());
        assertEquals(fresh, registry.capturePrepared(scopes.get(id))); assertTrue(bundles.authorities(first.job().scanRunId()).isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void schedulingRejectionAndExecutorShutdownRevokeAcceptedBundle(boolean shutdown) {
        long id = source(ROOT); var accepted = admit(id);
        Version2IndexingExecutor executor;
        if (shutdown) { executor = app.getBean(Version2IndexingExecutor.class); executor.destroy(); }
        else executor = new Version2IndexingExecutor(app.getBean(CatalogOwnership.class), app.getBean(Version2IndexingStartup.class)) {
            @Override public void execute(Runnable task) { throw new java.util.concurrent.RejectedExecutionException("Explicit queue rejection"); }
        };
        try {
            var background = new Version2BackgroundIndexingService(execution, executor, app.getBean(Version2InterruptionRecovery.class));
            background.exfatBundles(bundles);
            assertThrows(Version2SchedulingException.class, () -> background.submitAccepted(accepted));
            assertFalse(registry.project(scopes.get(id)).liveAuthorityAvailable()); assertEquals(0, count("file_entry"));
            assertEquals("FAILED", app.getBean(JobRepository.class).findJobById(accepted.job().id()).orElseThrow().status());
        } finally { if (!shutdown) executor.destroy(); }
    }
    @Test void publicProductionAdmissionStillRejectsEvenWithLivePreparedWindow() {
        long id = source(ROOT);
        var production = new ExfatScanBundles(app.getBean(CatalogRepository.class), app.getBean(LocationContextRepository.class),
                app.getBean(SourceBindingPeriodRepository.class), app.getBean(ScanRepository.class), app.getBean(JobRepository.class),
                registry, app.getBean(PlatformTransactionManager.class));
        assertThrows(Version2ExecutionConflictException.class, () -> production.admit(List.of(id), prepared -> { fail("Gate bypassed"); return null; }));
        assertFalse(WindowsExfatSupport.PRODUCTION.available()); assertEquals(0, count("job"));
    }
    @ParameterizedTest @ValueSource(strings = {"hash", "alias", "subtree", "end", "route", "resource"})
    void incompleteDiscoveryNeverSweepsMissingAndKeepsOnlyCommittedPositives(String failure) throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\old.jpg", "old"); var first = admit(id); execution.run(first.job().scanRunId());
        access.files.clear(); access.add("C:\\Photos\\new.jpg", "new"); access.add("C:\\Photos\\Blocked\\other.jpg", "other");
        switch (failure) {
            case "hash" -> access.failRead = true;
            case "alias" -> access.add("C:\\Photos\\NEW.jpg", "ambiguous");
            case "subtree" -> access.inaccessible = ROOT.append("Blocked");
            case "end" -> access.failEnd = true;
            case "route" -> access.wrongAfter = true;
            case "resource" -> access.add("C:\\Photos\\" + "Deep\\".repeat(100) + "file.jpg", "deep");
        }
        prepare(id); var next = admit(id); assertThrows(RuntimeException.class, () -> discover(next));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership WHERE file_entry_id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership WHERE presence_status='MISSING'", Long.class));
        if (failure.equals("subtree") || failure.equals("end") || failure.equals("resource")) assertTrue(count("file_entry") > 1, "Qualified earlier positives remain committed");
        else assertEquals(1, count("file_entry"));
    }
    @Test void positiveRollbackIncludesSupersessionReceiptMembershipAndProgressBeforeClose() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "old"); var first = admit(id); execution.run(first.job().scanRunId());
        var prior = onlyFile(); prepare(id); hooks.failProgress = true; var next = admit(id);
        assertThrows(RuntimeException.class, () -> discover(next));
        assertEquals(prior, onlyFile()); assertEquals(1, count("source_membership")); assertEquals(1, active());
        assertEquals(List.of(1L, 1L), access.rowsAtFileClose);
        assertEquals(0, app.getBean(JobRepository.class).findJobStageByJobIdAndType(next.job().id(), "DISCOVERY").orElseThrow().progressCompleted());
    }
    @Test void membershipRevisionOverflowRejectsSupersessionAtomically() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "bytes"); var first = admit(id); execution.run(first.job().scanRunId());
        jdbc.update("UPDATE source_membership SET membership_revision=9223372036854775807"); prepare(id); var next = admit(id);
        assertThrows(RuntimeException.class, () -> discover(next)); assertEquals(1, count("file_entry")); assertEquals(1, active());
    }
    @Test void nativeResolvedCollisionFailsClosedWithoutRetiringNativeMembership() {
        long id = source(ROOT);
        var memberRepo = app.getBean(SourceMembershipRepository.class);
        var file = memberRepo.insertResolved(new FileEntry(null, "RESOLVED", CONTEXT, lp(ROOT.append("photo.jpg")), key(ROOT.append("photo.jpg")),
                null, 5, 500L, 0, "jpg", 0, 1, 1));
        memberRepo.insert(new SourceMembership(null, id, file.id(), "photo.jpg", "photo.jpg", "ACTIVE", "PRESENT", 0, 0, 1, 1, null, null, 1L, 1L));
        access.add("C:\\Photos\\photo.jpg", "bytes"); var accepted = admit(id);
        assertThrows(RuntimeException.class, () -> discover(accepted)); assertEquals(1, active()); assertEquals(file, onlyFile());
    }

    @Test void completeTraversalCannotSweepAContradictoryNativeResolvedOccurrenceMissing() {
        long id = source(ROOT); var memberships = app.getBean(SourceMembershipRepository.class);
        var nativeFile = memberships.insertResolved(new FileEntry(null, "RESOLVED", CONTEXT, lp(ROOT.append("absent.jpg")),
                key(ROOT.append("absent.jpg")), null, 5, 500L, 0, "jpg", 0, 1, 1));
        memberships.insert(new SourceMembership(null, id, nativeFile.id(), "absent.jpg", "absent.jpg", "ACTIVE", "PRESENT", 0, 0,
                1, 1, null, null, 1L, 1L));
        var accepted = admit(id); var claims = discover(accepted);
        assertThrows(RuntimeException.class, () -> reconcile(accepted, claims));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership", String.class));
        assertEquals(nativeFile, onlyFile());
    }
    @Test void staleMissingClaimCannotBorrowReacquiredWindowOrAnotherSourceCompletion() {
        long a = source(ROOT), b = source(ROOT.append("Nested")); var accepted = admit(a, b); var claims = discover(accepted);
        var swapped = new HashMap<>(claims); swapped.put(a, claims.get(b));
        assertThrows(RuntimeException.class, () -> reconcile(accepted, swapped));
        registry.release(windows.get(a)); prepare(a);
        assertThrows(RuntimeException.class, () -> reconcile(accepted, claims)); assertEquals(0, count("file_entry"));
    }

    @Test void completeOldMissingClaimFailsAfterReleaseAndReacquisitionAtSameRevisions() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "old"); var first = admit(id); execution.run(first.job().scanRunId());
        access.files.clear(); prepare(id); var accepted = admit(id); var claims = discover(accepted);
        registry.release(windows.get(id)); var fresh = prepare(id);
        assertThrows(RuntimeException.class, () -> reconcile(accepted, claims));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership", String.class));
        assertEquals(fresh, registry.capturePrepared(scopes.get(id)));
    }

    @ParameterizedTest @ValueSource(strings = {"source", "context", "period", "generation", "job", "window"})
    void stalePositiveAuthorityPublishesNoReceiptMembershipOrProgress(String changed) {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "protected bytes"); var accepted = admit(id);
        access.onEvidence = () -> {
            if (access.evidenceReads != 2) return;
            switch (changed) {
                case "source" -> jdbc.update("UPDATE source SET location_revision=2");
                case "context" -> jdbc.update("UPDATE location_context SET revision=2");
                case "period" -> jdbc.update("UPDATE source_binding_period SET bound_at_ms=11");
                case "generation" -> jdbc.update("UPDATE scan_run_source SET traversal_generation=2");
                case "job" -> registry.handoff(scopes.get(id), windows.get(id), bundles.authorities(accepted.job().scanRunId()).getFirst().bundleUuid());
                case "window" -> { registry.release(windows.get(id)); prepare(id); }
            }
        };
        assertThrows(RuntimeException.class, () -> discover(accepted)); assertEquals(0, count("file_entry")); assertEquals(0, count("source_membership"));
        assertEquals(0, app.getBean(JobRepository.class).findJobStageByJobIdAndType(accepted.job().id(), "DISCOVERY").orElseThrow().progressCompleted());
        assertEquals(java.util.List.of(0L), access.rowsAtFileClose);
    }
}
