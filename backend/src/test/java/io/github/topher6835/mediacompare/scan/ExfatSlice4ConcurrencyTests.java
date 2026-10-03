package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;

class ExfatSlice4ConcurrencyTests extends ExfatSlice4TestSupport {
    @Test void uncertainRejectedAcquisitionCleanupDeniesFurtherRuntimeAcquisition() {
        long id = source(ROOT); var accepted = admit(id); access.acquisitionCloseFails = true;
        assertThrows(RuntimeException.class, () -> discover(accepted)); assertEquals(0, count("file_entry"));
        assertFalse(registry.project(scopes.get(id)).liveAuthorityAvailable()); assertThrows(RuntimeException.class, () -> prepare(id));
    }
    @Test void interruptedHashFailsThroughRecoveryWithoutInferringMissing() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\old.jpg", "old"); var prior = admit(id); execution.run(prior.job().scanRunId());
        access.files.clear(); access.add("C:\\Photos\\new.jpg", "new"); prepare(id); var accepted = admit(id);
        access.onRead = () -> Thread.currentThread().interrupt();
        try {
            assertThrows(IndexingInterruptedException.class, () -> execution.run(accepted.job().scanRunId()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        stop(accepted);
        assertEquals(1, count("file_entry")); assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership", String.class));
        assertFalse(registry.project(scopes.get(id)).liveAuthorityAvailable()); assertTrue(bundles.authorities(accepted.job().scanRunId()).isEmpty());
    }
    @Test void releaseDuringHashCancelsBeforePublicationAndDrainsRetainedResources() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "a protected file with several read buffers"); var accepted = admit(id);
        CountDownLatch hashing = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicBoolean first = new AtomicBoolean(true);
        access.onRead = () -> { if (first.getAndSet(false)) { hashing.countDown(); await(finish); } };
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> discover(accepted)); await(hashing);
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING, registry.release(windows.get(id)).releaseState());
            assertEquals(0, access.closes); assertFalse(registry.project(scopes.get(id)).liveAuthorityAvailable());
            finish.countDown(); assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals(0, count("file_entry")); assertEquals(0, count("source_membership")); assertEquals(1, access.closes);
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, registry.release(windows.get(id)).releaseState());
        } finally { finish.countDown(); worker.shutdownNow(); }
    }

    @Test void releaseWhileCommitPausedKeepsFileHeldThroughCommitAndPreservesCommittedPositive() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "protected bytes"); var accepted = admit(id);
        hooks.committing = new CountDownLatch(1); hooks.finishCommit = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> discover(accepted)); await(hooks.committing);
            assertEquals(0, count("file_entry"), "No provisional committed row while publication is paused");
            assertEquals(0, access.closes);
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING, registry.release(windows.get(id)).releaseState());
            assertEquals(0, access.closes); hooks.finishCommit.countDown();
            assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals(1, count("file_entry")); assertEquals(1, count("source_membership"));
            assertEquals(java.util.List.of(1L), access.rowsAtFileClose); assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership", String.class));
        } finally { hooks.finishCommit.countDown(); worker.shutdownNow(); }
    }

    @Test void queuedOldJobCannotBorrowReplacementWindowAtIdenticalDurableRevisions() {
        long id = source(ROOT); var accepted = admit(id); var old = windows.get(id);
        registry.release(old); var fresh = prepare(id); assertNotEquals(old.windowId(), fresh.windowId());
        assertThrows(RuntimeException.class, () -> execution.run(accepted.job().scanRunId())); stop(accepted);
        assertEquals(fresh, registry.capturePrepared(scopes.get(id))); assertEquals(0, access.opens); assertEquals(0, count("file_entry"));
    }

    @Test void uncertainFileCloseAfterCommitRevokesBundleWithoutUndoingEvidence() {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "protected bytes"); access.closeFails = true;
        var accepted = admit(id); assertThrows(RuntimeException.class, () -> discover(accepted));
        assertEquals(1, count("file_entry")); assertEquals(1, count("source_membership")); assertEquals(0, count("content_record"));
        assertNotNull(OccurrenceProfileValidation.requireExfat(onlyFile()));
        assertFalse(registry.project(scopes.get(id)).liveAuthorityAvailable()); assertEquals(java.util.List.of(1L), access.rowsAtFileClose);
    }

    @Test void writerBeforeRegistryGateIsRejectedWithoutLockInversion() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "protected bytes"); var accepted = admit(id); discover(accepted);
        var a = bundles.authorities(accepted.job().scanRunId()).getFirst();
        var transaction = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            app.getBean(LocationContextRepository.class).reserveWrite();
            bundles.requireCatalog(a, "DISCOVERY", "DISCOVERING");
        }));
        // The failed inversion must release the writer, allowing the correctly ordered path to finish.
        reconcile(accepted, java.util.Map.of(id, a.missingClaim())); assignment(accepted); hashing(accepted);
        assertEquals(1, count("content_hash")); assertEquals(1, access.opens);
    }
}
