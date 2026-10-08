package io.github.topher6835.mediacompare.catalog;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.Version3ScanExecutionService;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;

class WindowsExfatPreparationLifecycleTests {
    @TempDir Path directory;
    private ConfigurableApplicationContext application;
    private CatalogRepository sources;
    private WindowsExfatBindingWriter writer;
    private ExfatAuthorityWindowRegistry registry;
    private JdbcTemplate jdbc;
    private WindowsExfatSourcePreparationService preparation;
    private final List<Root> roots = new ArrayList<>();
    private boolean contradictory;
    private boolean wrongVolume;

    @BeforeEach void open() { application = runtime(directory.resolve("catalog.db")); wire(); }
    @AfterEach void close() { application.close(); }

    private ConfigurableApplicationContext runtime(Path database) {
        return new SpringApplicationBuilder(MediaCompareApplication.class).web(WebApplicationType.NONE)
                .run("--media-compare.test-catalog-override=true", "--spring.datasource.url=jdbc:sqlite:" + database
                        + "?foreign_keys=on&busy_timeout=5000", "--logging.level.root=ERROR",
                        "--logging.level.org.springframework=ERROR", "--logging.level.io.github.topher6835=ERROR");
    }
    private void wire() {
        sources = application.getBean(CatalogRepository.class);
        writer = application.getBean(WindowsExfatBindingWriter.class);
        registry = application.getBean(ExfatAuthorityWindowRegistry.class);
        jdbc = application.getBean(JdbcTemplate.class);
        preparation = new WindowsExfatSourcePreparationService(sources, writer, registry,
                new SessionSourceBoundary(null), root -> {
                    var held = new Root(contradictory ? path("C:\\WrongRoot") : ROOT);
                    if (wrongVolume) held.evidence = new WindowsExfatVolumeEvidence("EXFAT", "87654321", GUID, "C:\\");
                    roots.add(held); return held;
                }, enabledForTests());
    }
    @Test void publicPrepareAndCatalogProjectionRetainExactConfigurationAcrossReacquisition() throws Exception {
        Source initial = register();
        application.getBean(SourcePreparationService.class).exfatPreparation(preparation);
        // Bound-source public dispatch runs before the native READY early return.
        Source ready = preparation.prepare(initial.id());
        var controller = application.getBean(io.github.topher6835.mediacompare.web.SourceController.class);
        var before = durableRows();
        var original = controller.findById(ready.id()).getBody();
        assertEquals(FileSystemProfile.EXFAT, original.filesystemProfile());
        assertTrue(original.liveAuthorityAvailable());
        assertEquals(original, controller.prepare(ready.id()).getBody());
        assertEquals(1, roots.size());
        assertEquals(before, durableRows());
        var released = controller.release(ready.id(), new io.github.topher6835.mediacompare.web.ReleaseSourceAuthorityRequest(original.liveAuthorityWindowId())).getBody();
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, released.releaseState());
        assertEquals(SourcePreparationState.READY, released.source().preparationState());
        assertFalse(released.source().liveAuthorityAvailable());
        assertNull(released.source().liveAuthorityWindowId());
        var fresh = controller.prepare(ready.id()).getBody();
        assertTrue(fresh.liveAuthorityAvailable());
        assertNotEquals(original.liveAuthorityWindowId(), fresh.liveAuthorityWindowId());
        assertEquals(before, durableRows());
        assertEquals(2, roots.size());
        assertEquals(ready, sources.findSourceById(ready.id()).orElseThrow());
    }

    @Test void unavailableRetainedRootIsRevokedAndRequiresSeparateExplicitPrepare() {
        Source ready = preparation.prepare(register().id());
        var before = durableRows();
        roots.getFirst().available = false;
        assertFalse(application.getBean(SourceAuthorityProjection.class).project(ready).liveAuthorityAvailable());
        assertEquals(SourcePreparationException.Code.EVIDENCE_UNCERTAIN,
                assertThrows(SourcePreparationException.class, () -> preparation.prepare(ready.id())).code());
        assertEquals(1, roots.size()); // No replacement acquisition in the invalidating request.
        assertEquals(before, durableRows());
        assertEquals(ready, preparation.prepare(ready.id()));
        assertEquals(2, roots.size());
        assertTrue(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
        assertEquals(before, durableRows());
    }

    private Source register() { return sources.insert(new Source(null, "Pictures", "C:\\Photos", "C:\\Photos", 0, 1, 1)); }

    @Test void firstBindCreatesEnvelopesPeriodAndWindowWhileProductionIndexingRemainsDisabled() {
        Source source = register();
        Source ready = preparation.prepare(source.id());
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(ready));
        assertEquals(1, ready.locationRevision());
        var scope = writer.snapshot(source.id());
        assertEquals(1, scope.context().revision());
        assertEquals(WindowsExfatSourceEvidence.VERSION, new WindowsExfatEvidenceCodec().decodeSource(ready.bindingEvidenceJson()).version());
        assertEquals(WindowsExfatContextEvidence.VERSION,
                new WindowsExfatEvidenceCodec().decodeContext(scope.context().continuityEvidenceJson()).version());
        assertTrue(preparation.liveAuthority(source.id()).liveAuthorityAvailable());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_binding_period", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry", Integer.class));
        assertFalse(WindowsExfatSupport.PRODUCTION.available());
        assertThrows(IllegalArgumentException.class, () -> CurrentLocationAuthority.requireCurrentHost(ready, scope.context()));
        SourcePreparationException denied = assertThrows(SourcePreparationException.class,
                () -> application.getBean(SourcePreparationService.class).prepare(source.id()));
        assertEquals(SourcePreparationException.Code.PROFILE_UNSUPPORTED, denied.code());
        var request = application.getBean(ScanRunService.class).create(List.of(source.id()));
        assertThrows(RuntimeException.class, () -> application.getBean(Version3ScanExecutionService.class).create(request.scanRun().id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry WHERE occurrence_token IS NOT NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership", Integer.class));
    }

    @Test void releaseReacquisitionAndIdempotentPrepareChangeNoDurableRowsIncludingLastKnownMemberships() {
        Source ready = preparation.prepare(register().id());
        seedHistoricalMembership(ready.id());
        var before = durableRows();
        var scope = writer.snapshot(ready.id());
        var first = registry.capturePrepared(scope);
        assertEquals(ready, preparation.prepare(ready.id()));
        assertEquals(first, registry.capturePrepared(scope));
        assertEquals(1, roots.size());
        assertTrue(roots.getFirst().validations.get() > 1);
        registry.release(first);
        assertFalse(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(sources.findSourceById(ready.id()).orElseThrow()));
        assertEquals(before, durableRows());
        assertEquals(ready, preparation.prepare(ready.id()));
        var second = registry.capturePrepared(scope);
        assertNotEquals(first, second);
        assertThrows(IllegalStateException.class, () -> registry.requireExact(scope, first));
        assertEquals(before, durableRows());
        assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership", String.class));
        assertEquals(1, roots.getFirst().closes.get());
    }

    @Test void actualCatalogRestartLoadsReadyWithoutLiveAuthorityAndRequiresExplicitAcceptance() {
        Source ready = preparation.prepare(register().id());
        seedHistoricalMembership(ready.id());
        var before = durableRows();
        var oldScope = writer.snapshot(ready.id());
        var old = registry.capturePrepared(oldScope);
        application.close();
        assertEquals(1, roots.getFirst().closes.get());
        application = runtime(directory.resolve("catalog.db")); wire();
        assertEquals(ready, sources.findSourceById(ready.id()).orElseThrow());
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(ready));
        assertFalse(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
        assertEquals(before, durableRows());
        assertThrows(IllegalStateException.class, () -> registry.requireExact(writer.snapshot(ready.id()), old));
        assertThrows(IllegalArgumentException.class, () -> CurrentLocationAuthority.requireCurrentHost(ready, oldScope.context()));
        assertEquals(ready, preparation.prepare(ready.id()));
        var fresh = registry.capturePrepared(writer.snapshot(ready.id()));
        assertNotEquals(old.runtimeId(), fresh.runtimeId());
        assertNotEquals(old.windowId(), fresh.windowId());
        assertEquals(before, durableRows());
    }

    @Test void contradictionsChangedConfigurationAndInvalidLiveRetainedAuthorityRefuseReacquisition() {
        Source ready = preparation.prepare(register().id());
        var scope = writer.snapshot(ready.id());
        var before = durableRows();
        registry.release(registry.capturePrepared(scope));
        contradictory = true;
        assertThrows(SourcePreparationException.class, () -> preparation.prepare(ready.id()));
        assertEquals(1, roots.getLast().closes.get());
        assertEquals(before, durableRows());
        contradictory = false;
        wrongVolume = true;
        assertThrows(SourcePreparationException.class, () -> preparation.prepare(ready.id()));
        assertEquals(1, roots.getLast().closes.get());
        assertEquals(before, durableRows());
        wrongVolume = false;
        preparation.prepare(ready.id());
        var id = registry.capturePrepared(scope);
        roots.getLast().fail = true;
        assertThrows(SourcePreparationException.class, () -> preparation.prepare(ready.id()));
        assertFalse(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
        registry.release(id);
        assertEquals(before, durableRows());
        jdbc.update("UPDATE source SET root_path = 'C:\\Elsewhere' WHERE id = ?", ready.id());
        assertThrows(RuntimeException.class, () -> preparation.prepare(ready.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_binding_period", Integer.class));
    }

    @Test void trueUnbindInvalidatesAndKeepsRebindSeparateFromPrepare() {
        Source ready = preparation.prepare(register().id());
        var id = registry.capturePrepared(writer.snapshot(ready.id()));
        Source unbound = application.getBean(SourceUnbindingService.class).unbind(ready.id(), ready.locationRevision(), ready.updatedAtMs() + 1);
        assertEquals(SourcePreparationState.REBIND_REQUIRED, SourcePreparationState.from(unbound));
        assertFalse(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
        assertEquals(SourcePreparationException.Code.STATE_CHANGED,
                assertThrows(SourcePreparationException.class, () -> preparation.prepare(ready.id())).code());
        registry.release(id);
        Source rebound = preparation.rebind(ready.id(), ready.boundLocationContextId());
        assertEquals(3, rebound.locationRevision());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM source_binding_period", Integer.class));
        assertTrue(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
    }

    @Test void caseVariantConfiguredKeyPreservesExistingStructuredUnboundInvariant() {
        Source source = sources.insert(new Source(null, "case", "c:\\photos", "c:\\photos", 0, 1, 1));
        Source ready = preparation.prepare(source.id());
        var evidence = new WindowsExfatEvidenceCodec().decodeSource(ready.bindingEvidenceJson());
        assertNotEquals(evidence.configuredRootLocationKey(), evidence.resolvedRootLocationKey());
        assertEquals(evidence.configuredRootLocationKey(), ready.rootPathKey());
        Source unbound = application.getBean(SourceUnbindingService.class).unbind(ready.id(), ready.locationRevision(), ready.updatedAtMs() + 1);
        assertEquals(SourcePreparationState.REBIND_REQUIRED, SourcePreparationState.from(unbound));
        assertEquals(3, preparation.rebind(ready.id(), ready.boundLocationContextId()).locationRevision());
    }

    @Test void acquisitionThatOutlivesShutdownCannotInstallAndClosesNewHandles() {
        Source source = register();
        var held = new Root(ROOT);
        var custom = new WindowsExfatSourcePreparationService(sources, writer, registry, new SessionSourceBoundary(null),
                root -> { registry.shutdown(); return held; }, enabledForTests());
        assertThrows(SourcePreparationException.class, () -> custom.prepare(source.id()));
        assertEquals(1, held.closes.get());
        assertEquals(SourcePreparationState.PREPARATION_REQUIRED, SourcePreparationState.from(sources.findSourceById(source.id()).orElseThrow()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM location_context", Integer.class));
    }

    @Test void acquisitionRereadsEvidenceCapturedBeforeNativeIoAndRejectsSameRevisionChanges() {
        Source ready = preparation.prepare(register().id());
        registry.release(registry.capturePrepared(writer.snapshot(ready.id())));
        var held = new Root(ROOT);
        var custom = new WindowsExfatSourcePreparationService(sources, writer, registry, new SessionSourceBoundary(null),
                root -> {
                    jdbc.update("UPDATE location_context SET continuity_evidence_json = continuity_evidence_json || ' ' WHERE id = ?",
                            ready.boundLocationContextId());
                    return held;
                }, enabledForTests());
        assertEquals(SourcePreparationException.Code.STATE_CHANGED,
                assertThrows(SourcePreparationException.class, () -> custom.prepare(ready.id())).code());
        assertEquals(1, held.closes.get());
        assertFalse(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
        assertEquals(ready, sources.findSourceById(ready.id()).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_binding_period", Integer.class));
    }

    @Test void prepareCannotInstallInsideAnUncommittedCallerTransaction() {
        Source source = register();
        new TransactionTemplate(application.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            assertEquals(SourcePreparationException.Code.STATE_CHANGED,
                    assertThrows(SourcePreparationException.class, () -> preparation.prepare(source.id())).code());
        });
        assertTrue(roots.isEmpty());
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_binding_period", Integer.class));
    }

    @Test void firstBindCollisionAndUnsupportedStorageFormsFailWithoutCatalogChanges() {
        var anchor = path("c:\\");
        application.getBean(LocationContextRepository.class).insert(new LocationContext(CONTEXT, lp(anchor), key(anchor),
                LocationContext.LifecycleStatus.ACTIVE, LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, 1, 1));
        Source source = register();
        var before = durableRows();
        assertThrows(SourcePreparationException.class, () -> preparation.prepare(source.id()));
        assertEquals(before, durableRows());
        for (String unsupported : List.of("\\\\server\\share", "\\\\?\\C:\\Photos", "C:\\Photos\\..\\Other")) {
            Source raw = sources.insert(new Source(null, "unsupported", unsupported, unsupported, 0, 1, 1));
            assertThrows(SourcePreparationException.class, () -> preparation.prepare(raw.id()));
        }
    }

    @Test void publicationGateSpansActualTransactionCommitWhileReleaseRevokesImmediately() throws Exception {
        Source ready = preparation.prepare(register().id());
        var scope = writer.snapshot(ready.id());
        var id = registry.capturePrepared(scope);
        var atCommit = new CountDownLatch(1);
        var finishCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publication = executor.submit(() -> {
                try (var operation = registry.requireExact(scope, id)) {
                    new TransactionTemplate(application.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                atCommit.countDown();
                                try { assertTrue(finishCommit.await(5, TimeUnit.SECONDS)); }
                                catch (InterruptedException failure) { throw new RuntimeException(failure); }
                            }
                        });
                        writer.reread(scope);
                    });
                }
                return null;
            });
            assertTrue(atCommit.await(5, TimeUnit.SECONDS));
            var release = executor.submit(() -> registry.release(id));
            // The installed window is revoked immediately, even though its publication is committing.
            for (int i = 0; i < 200 && preparation.liveAuthority(ready.id()).liveAuthorityAvailable(); i++) Thread.sleep(5);
            assertFalse(preparation.liveAuthority(ready.id()).liveAuthorityAvailable());
            assertEquals(0, roots.getFirst().closes.get());
            finishCommit.countDown();
            publication.get(5, TimeUnit.SECONDS);
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED, release.get(5, TimeUnit.SECONDS).releaseState());
            assertEquals(1, roots.getFirst().closes.get());
        } finally { finishCommit.countDown(); }
    }

    private void seedHistoricalMembership(long sourceId) {
        jdbc.update("INSERT INTO file_entry (id,location_identity_status,size_bytes,first_seen_at_ms,last_seen_at_ms) VALUES (40,'UNRESOLVED',0,1,1)");
        jdbc.update("""
                INSERT INTO source_membership (id,source_id,file_entry_id,relative_path,path_key,
                    applicability_status,presence_status,observed_file_entry_revision,first_seen_at_ms,last_seen_at_ms)
                VALUES (70,?,40,'old.jpg','old.jpg','ACTIVE','PRESENT',0,1,1)
                """, sourceId);
    }
    private List<List<Map<String, Object>>> durableRows() {
        return List.of("source", "location_context", "source_binding_period", "source_membership", "file_entry", "content_record")
                .stream().map(table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")).toList();
    }
}
