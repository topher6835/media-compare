package io.github.topher6835.mediacompare.catalog;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import io.github.topher6835.mediacompare.filesystem.*;

/** Actual encoded evidence; dispatch/state/lifecycle checks need no host filesystem access. */
class WindowsDurableEvidenceDispatchTests {
    private final CatalogRepository sources = mock(CatalogRepository.class);
    private final LocationContextRepository contexts = mock(LocationContextRepository.class);
    private final WindowsExfatSourcePreparationService exfat = mock(WindowsExfatSourcePreparationService.class);
    private final WindowsNtfsSourcePreparationService ntfs = mock(WindowsNtfsSourcePreparationService.class);
    private final SourcePreparationProbe probe = mock(SourcePreparationProbe.class);
    private final ExfatAuthorityWindowRegistry registry = mock(ExfatAuthorityWindowRegistry.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private SourcePreparationService preparation;
    private ExfatLifecycleTransactions lifecycle;

    @BeforeEach void services() {
        preparation = new SourcePreparationService(sources, contexts, mock(LocationContextActivationService.class),
                mock(LocationContextAcceptanceService.class), mock(SourceBindingService.class), probe, ntfs);
        preparation.exfatPreparation(exfat);
        lifecycle = new ExfatLifecycleTransactions(registry, manager, sources, contexts);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(registry.transition(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    }

    @Test void identifiesExactNtfsArrayTagsAndExfatObjectVersions() {
        assertEquals(WindowsDurableEvidenceFormat.NTFS_SOURCE,
                WindowsDurableEvidenceFormat.identify(ntfsSource().bindingEvidenceJson()));
        assertEquals(WindowsDurableEvidenceFormat.NTFS_CONTEXT,
                WindowsDurableEvidenceFormat.identify(ntfsContext().continuityEvidenceJson()));
        assertEquals(WindowsDurableEvidenceFormat.EXFAT_SOURCE,
                WindowsDurableEvidenceFormat.identify(scope().source().bindingEvidenceJson()));
        assertEquals(WindowsDurableEvidenceFormat.EXFAT_CONTEXT,
                WindowsDurableEvidenceFormat.identify(scope().context().continuityEvidenceJson()));
        assertEquals(FileSystemProfile.NTFS, WindowsDurableEvidenceFormat.sourceProfile(ntfsSource().bindingEvidenceJson()));
        assertEquals(FileSystemProfile.EXFAT, WindowsDurableEvidenceFormat.contextProfile(scope().context().continuityEvidenceJson()));
        assertThrows(IllegalArgumentException.class,
                () -> WindowsDurableEvidenceFormat.sourceProfile(ntfsContext().continuityEvidenceJson()));
        assertThrows(IllegalArgumentException.class,
                () -> WindowsDurableEvidenceFormat.contextProfile(scope().source().bindingEvidenceJson()));
    }

    @Test void validPairsRemainReadyAndUseTheirOwnAuthorityProfile() {
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(ntfsSource()));
        assertEquals("ntfs", CurrentLocationAuthority.requirePersisted(ntfsSource(), ntfsContext()).fileSystemType());
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(scope().source()));
        assertEquals("exfat", CurrentLocationAuthority.requirePersisted(scope().source(), scope().context()).fileSystemType());
        assertFalse(WindowsExfatSupport.PRODUCTION.available());
        assertThrows(IllegalArgumentException.class,
                () -> CurrentLocationAuthority.requireCurrentHost(scope().source(), scope().context()));
    }

    @Test void mixedProfilesFailBeforeEitherPreparationPath() {
        assertThrows(IllegalStateException.class,
                () -> CurrentLocationAuthority.requirePersisted(scope().source(), ntfsContext()));
        assertThrows(IllegalStateException.class,
                () -> CurrentLocationAuthority.requirePersisted(ntfsSource(), scope().context()));
        rows(scope().source(), ntfsContext());
        assertThrows(IllegalStateException.class, () -> preparation.prepare(1));
        rows(ntfsSource(), scope().context());
        assertThrows(IllegalStateException.class, () -> preparation.prepare(1));
        verifyNoInteractions(exfat, ntfs, probe);
    }

    @Test void malformedUnknownAndWrongVersionsNeverBecomeReadyOrSelectPreparation() {
        for (String document : invalidDocuments()) {
            Source source = sourceWithEvidence(document);
            rows(source, scope().context());
            assertThrows(IllegalArgumentException.class, () -> SourcePreparationState.from(source), document);
            assertThrows(IllegalArgumentException.class,
                    () -> CurrentLocationAuthority.requirePersisted(source, scope().context()), document);
            assertThrows(IllegalArgumentException.class, () -> preparation.prepare(1), document);
            LocationContext context = contextWithEvidence(document);
            rows(scope().source(), context);
            assertThrows(IllegalArgumentException.class,
                    () -> CurrentLocationAuthority.requirePersisted(scope().source(), context), document);
            assertThrows(IllegalArgumentException.class, () -> preparation.prepare(1), document);
        }
        verifyNoInteractions(exfat, ntfs, probe);
        assertEquals(WindowsDurableEvidenceFormat.MALFORMED, WindowsDurableEvidenceFormat.identify("{}"));
        assertEquals(WindowsDurableEvidenceFormat.MALFORMED, WindowsDurableEvidenceFormat.identify("{"));
        assertEquals(WindowsDurableEvidenceFormat.MALFORMED, WindowsDurableEvidenceFormat.identify(null));
        assertEquals(WindowsDurableEvidenceFormat.UNKNOWN,
                WindowsDurableEvidenceFormat.identify("{\"version\":\"future-windows-v1\"}"));
    }

    @Test void knownVersionsStillRequireFullStrictCodecValidation() {
        for (String document : List.of("{\"version\":\"windows-exfat-source-v1\"}",
                "[\"windows-ntfs-source-v1\"]",
                scope().source().bindingEvidenceJson().replace("\"directory\":true", "\"directory\":false"))) {
            Source source = sourceWithEvidence(document);
            rows(source, scope().context());
            assertThrows(IllegalArgumentException.class, () -> SourcePreparationState.from(source));
            assertThrows(IllegalArgumentException.class, () -> preparation.prepare(1));
            assertThrows(IllegalArgumentException.class, () -> lifecycle.source(1, () -> fail("Invalid Source transition")));
        }
        LocationContext context = contextWithEvidence("{\"version\":\"windows-exfat-context-v1\"}");
        rows(scope().source(), context);
        assertThrows(IllegalArgumentException.class,
                () -> CurrentLocationAuthority.requirePersisted(scope().source(), context));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.context(CONTEXT, () -> fail("Invalid context transition")));
        verifyNoInteractions(exfat, ntfs, probe);
        verify(registry, never()).transition(any());
    }

    @Test void ordinaryWhitespaceDispatchesThroughTheSelectedStrictCodec() {
        Source source = sourceWithEvidence(" \t\r\n" + scope().source().bindingEvidenceJson() + "\n ");
        LocationContext context = contextWithEvidence("\n\t " + scope().context().continuityEvidenceJson() + "\r\n");
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(source));
        assertEquals("exfat", CurrentLocationAuthority.requirePersisted(source, context).fileSystemType());
        rows(source, context);
        when(exfat.prepare(1)).thenReturn(source);
        assertEquals(source, preparation.prepare(1));
        verify(exfat).prepare(1);
        verifyNoInteractions(ntfs, probe);
    }

    @Test void readyNtfsPreparationIsIdempotentAndNeverInterceptedByExfat() {
        rows(ntfsSource(), ntfsContext());
        assertEquals(ntfsSource(), preparation.prepare(1));
        verifyNoInteractions(exfat, ntfs, probe);
    }

    @Test void lifecycleUsesNtfsTransactionsAndExfatGateOnlyForKnownValidatedProfiles() {
        rows(ntfsSource(), ntfsContext());
        assertEquals("native-source", lifecycle.source(1, () -> "native-source"));
        assertEquals("native-context", lifecycle.context(CONTEXT, () -> "native-context"));
        verify(manager, times(2)).getTransaction(any());
        verify(registry, never()).transition(any());
        rows(scope().source(), scope().context());
        assertEquals("exfat-source", lifecycle.source(1, () -> "exfat-source"));
        assertEquals("exfat-context", lifecycle.context(CONTEXT, () -> "exfat-context"));
        verify(registry, times(2)).transition(any());
        verify(registry).invalidateSource(1);
        verify(registry).invalidateVolume(CONTEXT);
    }

    @Test void unknownLifecycleEvidenceFailsClosedAndStillRevokesAlreadyOwnedAuthority() {
        for (String document : invalidDocuments()) {
            rows(sourceWithEvidence(document), contextWithEvidence(document));
            assertThrows(IllegalArgumentException.class, () -> lifecycle.source(1, () -> fail("Unknown Source transition")));
            assertThrows(IllegalArgumentException.class, () -> lifecycle.context(CONTEXT, () -> fail("Unknown context transition")));
        }
        verifyNoInteractions(manager);
        verify(registry, never()).transition(any());
        when(registry.ownsSource(1)).thenReturn(true);
        when(registry.ownsContext(CONTEXT)).thenReturn(true);
        rows(sourceWithEvidence("{}"), contextWithEvidence("{}"));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.source(1, () -> fail("Unknown Source transition")));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.context(CONTEXT, () -> fail("Unknown context transition")));
        verify(registry).invalidateSource(1);
        verify(registry).invalidateVolume(CONTEXT);
        verify(manager, times(2)).getTransaction(any());
        verify(manager, times(2)).rollback(any());
        verify(manager, never()).commit(any());
    }

    private void rows(Source source, LocationContext context) {
        when(sources.findSourceById(1)).thenReturn(Optional.of(source));
        when(contexts.findById(CONTEXT)).thenReturn(Optional.of(context));
    }

    private static List<String> invalidDocuments() {
        return List.of("{}", "{", "null", "[]", "{\"version\":false}",
                "{\"version\":\"future-windows-v1\"}",
                "{\"version\":\"windows-exfat-source-v2\"}",
                "{\"version\":\"windows-exfat-context-v2\"}",
                "{\"version\":\"windows-ntfs-source-v1\"}",
                "[\"windows-exfat-source-v1\"]", "[\"windows-ntfs-source-v2\"]",
                "{\"version\":\"windows-exfat-source-v1\",\"version\":\"windows-ntfs-source-v1\"}",
                scope().source().bindingEvidenceJson() + " {}",
                ntfsSource().bindingEvidenceJson() + " {}");
    }

    private static Source sourceWithEvidence(String evidence) {
        var source = scope().source();
        return new Source(source.id(), source.name(), source.rootPath(), source.rootPathKey(), source.locationRevision(),
                source.rootPathDialect(), source.boundLocationContextId(), evidence, source.createdAtMs(), source.updatedAtMs());
    }

    private static LocationContext contextWithEvidence(String evidence) {
        var context = scope().context();
        return new LocationContext(context.id(), context.anchorLocationPath(), context.anchorLocationKey(),
                context.lifecycleStatus(), context.continuityStatus(), context.revision(), evidence,
                context.createdAtMs(), context.updatedAtMs());
    }

    private static Source ntfsSource() {
        var evidence = new WindowsNtfsSourceEvidence(1, 1, CONTEXT, 1, ROOT,
                new WindowsNtfsIdentity("0000000012345678", "00000000000000000000000000000002"));
        return sourceWithEvidence(new WindowsNtfsEvidenceCodec().encode(evidence));
    }

    private static LocationContext ntfsContext() {
        var evidence = new WindowsNtfsContextEvidence(CONTEXT, 1, path("C:\\"),
                new WindowsNtfsIdentity("0000000012345678", "00000000000000000000000000000001"));
        return contextWithEvidence(new WindowsNtfsEvidenceCodec().encode(evidence));
    }
}
