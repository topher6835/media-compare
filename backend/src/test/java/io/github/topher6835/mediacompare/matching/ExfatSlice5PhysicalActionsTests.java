package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import org.junit.jupiter.api.Test;

class ExfatSlice5PhysicalActionsTests {
    @Test void nativeNonMacHostPrecedenceIncludesAbsentPositiveIdsAndExfatDenialPrecedesHost() {
        var catalog = new RevealCatalog(null, null, null) {
            @Override public boolean isExfat(long id) { return id == 42; }
            @Override public CleanupPreflightCatalog.PhysicalFile capture(long id) { fail("Unsupported host captured a physical route"); return null; }
        };
        var reveal = new RevealFileService(catalog, null, null, () -> false);
        assertEquals(RevealFileService.Result.NOT_FOUND, reveal.reveal(0));
        assertEquals(RevealFileService.Result.UNSUPPORTED_HOST, reveal.reveal(999));
        assertEquals(RevealFileService.Result.STALE_AUTHORITY, reveal.reveal(42));
    }
    @Test void additiveRequestModesAndCanonicalWindowIdsFailClosed() {
        String invalidVariant = "00000000-0000-4000-0000-000000000000";
        assertThrows(IllegalArgumentException.class, () -> new io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest(1, invalidVariant));
        assertThrows(IllegalArgumentException.class, () -> new io.github.topher6835.mediacompare.web.CreateMediaMetadataRunRequest(null, null));
        var window = new io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest(1, java.util.UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> new io.github.topher6835.mediacompare.web.CreateMediaMetadataRunRequest(1L, java.util.List.of(window)));
        assertThrows(IllegalArgumentException.class, () -> new io.github.topher6835.mediacompare.web.CreateMediaMetadataRunRequest(null, java.util.List.of(window, window)));
    }

    @Test void validReceiptRequiresStrictMatchingPersistedContextAndMixedEvidenceIsIntegrityFailure() {
        var receipt = ExfatReceiptTestFixtures.receipt();
        var entry = new FileEntry(1L, "RESOLVED", receipt.contextId(), receipt.locationPath(), receipt.locationKey(),
                1L, receipt.sizeBytes(), receipt.modifiedTimeEpochSecond(), receipt.modifiedTimeNano(), "jpg",
                receipt.fileObservationRevision(), 1, 1, receipt.occurrenceToken(), new ExfatObservationReceiptCodec().encode(receipt));
        var volume = new WindowsExfatVolumeEvidence("EXFAT", receipt.before().volumeSerial(), receipt.before().volumeGuid(), "X:\\");
        String context = ExfatSlice3Fixtures.scope(1, receipt.contextId(), 1, 1, ExfatReceiptTestFixtures.ROOT, volume)
                .context().continuityEvidenceJson();
        assertTrue(PersistedPhysicalActions.isExfat(entry, context));
        assertThrows(IllegalArgumentException.class, () -> PersistedPhysicalActions.isExfat(entry, "{}"));
        var nativeEntry = new FileEntry(entry.id(), entry.locationIdentityStatus(), entry.locationContextId(), entry.locationPath(),
                entry.locationKey(), entry.currentContentId(), entry.sizeBytes(), entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano(),
                entry.extensionKey(), entry.observationRevision(), 1, 1);
        assertThrows(IllegalArgumentException.class, () -> PersistedPhysicalActions.isExfat(nativeEntry, context));
    }
}
