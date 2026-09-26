package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceService;
import io.github.topher6835.mediacompare.catalog.LocationContextActivationService;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriodRepository;
import io.github.topher6835.mediacompare.catalog.SourcePreparationException;
import io.github.topher6835.mediacompare.catalog.SourcePreparationProbe;
import io.github.topher6835.mediacompare.catalog.SourcePreparationService;
import io.github.topher6835.mediacompare.catalog.SourcePreparationState;
import io.github.topher6835.mediacompare.catalog.SourceService;
import io.github.topher6835.mediacompare.catalog.SourceUnbindingService;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.ContinuityReason;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootProbeRequest;

@SpringBootTest
@Import(SourcePreparationServiceTests.ProbeConfiguration.class)
class SourcePreparationServiceTests {
    @TempDir static Path databaseDirectory;
    private static final String DATA_VOLUME = "11111111-2222-3333-4444-555555555555";
    private static final LocationPath USERS = unix("/Users");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("preparation.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository sourceRows;
    @Autowired private SourceService sources;
    @Autowired private SourcePreparationService preparation;
    @Autowired private SourceUnbindingService unbinding;
    @Autowired private LocationContextRepository contexts;
    @Autowired private LocationContextActivationService activation;
    @Autowired private LocationContextAcceptanceService acceptance;
    @Autowired private SourceBindingPeriodRepository periods;
    @Autowired private FakeProbe probe;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM content_hash");
        jdbc.update("DELETE FROM analysis_record");
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM scan_run_source");
        jdbc.update("DELETE FROM job_stage");
        jdbc.update("DELETE FROM job");
        jdbc.update("DELETE FROM scan_run");
        jdbc.update("DELETE FROM source_binding_period");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
        probe.reset();
    }

    @Test
    void firstPreparationCreatesAndAcceptsLogicalContextThenBindsSource() {
        Source source = register("Pictures", "/Users/chris/Pictures");
        assertEquals(SourcePreparationState.PREPARATION_REQUIRED, SourcePreparationState.from(source));

        Source ready = preparation.prepare(source.id());

        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(ready));
        assertEquals(source.rootPath(), ready.rootPath());
        assertEquals(1, contexts.findActive().size());
        LocationContext context = contexts.findActive().getFirst();
        assertEquals(new LocationPathCodec().encode(USERS), context.anchorLocationPath());
        assertEquals(LocationContext.ContinuityStatus.ACCEPTED, context.continuityStatus());
        assertEquals(context.id(), ready.boundLocationContextId());
        assertEquals(1, periods.findBySourceId(source.id()).size());
        assertEquals(2, probe.contextCalls);
    }

    @Test
    void reusesExistingAcceptedContext() {
        LocationContext context = acceptedContext(USERS);
        Source source = register("Pictures", "/Users/chris/Pictures");

        Source ready = preparation.prepare(source.id());

        assertEquals(context.id(), ready.boundLocationContextId());
        assertEquals(1, contexts.findActive().size());
        assertEquals(1, probe.contextCalls);
    }

    @Test
    void reusesAcceptedAncestorWithinTheDiscoveredLogicalDomain() {
        LocationContext context = acceptedContext(unix("/Users/chris"));
        Source source = register("Pictures", "/Users/chris/Pictures");

        assertEquals(context.id(), preparation.prepare(source.id()).boundLocationContextId());
        assertEquals(1, contexts.findActive().size());
    }

    @Test
    void acceptsApplicableReviewRequiredContext() {
        long now = System.currentTimeMillis();
        LocationContext review = activation.createActive(new LocationContext(
                UUID.randomUUID().toString(), new LocationPathCodec().encode(USERS),
                LocationKeyCodec.encode(USERS).value(), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, now, now));
        Source source = register("Pictures", "/Users/chris/Pictures");

        Source ready = preparation.prepare(source.id());

        assertEquals(review.id(), ready.boundLocationContextId());
        assertEquals(LocationContext.ContinuityStatus.ACCEPTED,
                contexts.findById(review.id()).orElseThrow().continuityStatus());
        assertEquals(1, contexts.findActive().size());
    }

    @Test
    void siblingSourcesReuseTheSameLogicalContext() {
        Source pictures = preparation.prepare(register("Pictures", "/Users/chris/Pictures").id());
        Source videos = preparation.prepare(register("Videos", "/Users/chris/Videos").id());

        assertEquals(pictures.boundLocationContextId(), videos.boundLocationContextId());
        assertEquals(1, contexts.findActive().size());
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(videos));
    }

    @Test
    void readyPreparationIsIdempotent() {
        Source ready = preparation.prepare(register("Pictures", "/Users/chris/Pictures").id());
        int contextCalls = probe.contextCalls;

        assertEquals(ready, preparation.prepare(ready.id()));
        assertEquals(1, contexts.findActive().size());
        assertEquals(1, periods.findBySourceId(ready.id()).size());
        assertEquals(contextCalls, probe.contextCalls);
    }

    @Test
    void malformedBoundEvidenceCannotReportReady() {
        Source ready = preparation.prepare(register("Pictures", "/Users/chris/Pictures").id());
        Source malformed = new Source(ready.id(), ready.name(), ready.rootPath(), ready.rootPathKey(),
                ready.locationRevision(), ready.rootPathDialect(), ready.boundLocationContextId(),
                "{}", ready.createdAtMs(), ready.updatedAtMs());

        assertThrows(IllegalStateException.class, () -> SourcePreparationState.from(malformed));
    }

    @Test
    void structuredUnboundSourceRequiresRebinding() {
        Source ready = preparation.prepare(register("Pictures", "/Users/chris/Pictures").id());
        Source unbound = unbinding.unbind(ready.id(), ready.locationRevision(),
                Math.max(System.currentTimeMillis(), ready.updatedAtMs()));
        assertEquals(SourcePreparationState.REBIND_REQUIRED, SourcePreparationState.from(unbound));

        assertFailure(SourcePreparationException.Code.STATE_CHANGED, unbound.id());
        assertEquals(unbound, sourceRows.findSourceById(unbound.id()).orElseThrow());
        assertEquals(1, periods.findBySourceId(unbound.id()).size());
    }

    @Test
    void unavailableAndUnsupportedRootsLeaveSourceUnbound() {
        Source source = register("Pictures", "/Users/chris/Pictures");
        probe.anchorFailure = ContinuityProbeResult.unavailable();
        assertFailure(SourcePreparationException.Code.PATH_UNAVAILABLE, source.id());
        probe.anchorFailure = ContinuityProbeResult.unsupported();
        assertFailure(SourcePreparationException.Code.PROFILE_UNSUPPORTED, source.id());
        assertEquals(source, sourceRows.findSourceById(source.id()).orElseThrow());
        assertTrue(contexts.findActive().isEmpty());
    }

    @Test
    void uncertainAndErroredAnchorLeaveSourceUnbound() {
        Source source = register("Pictures", "/Users/chris/Pictures");
        probe.anchorFailure = ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        assertFailure(SourcePreparationException.Code.EVIDENCE_UNCERTAIN, source.id());
        probe.anchorFailure = ContinuityProbeResult.error();
        assertFailure(SourcePreparationException.Code.PROBE_ERROR, source.id());
        assertEquals(source, sourceRows.findSourceById(source.id()).orElseThrow());
    }

    @Test
    void failedContextAndRootProbesNeverBindSource() {
        Source source = register("Pictures", "/Users/chris/Pictures");
        probe.contextFailure = ContinuityProbeResult.unavailable();
        assertFailure(SourcePreparationException.Code.PATH_UNAVAILABLE, source.id());
        assertEquals(SourcePreparationState.PREPARATION_REQUIRED,
                SourcePreparationState.from(sourceRows.findSourceById(source.id()).orElseThrow()));

        probe.contextFailure = null;
        probe.rootFailure = ContinuityProbeResult.mismatch(ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH);
        assertFailure(SourcePreparationException.Code.EVIDENCE_UNCERTAIN, source.id());
        assertEquals(source, sourceRows.findSourceById(source.id()).orElseThrow());
        assertTrue(periods.findBySourceId(source.id()).isEmpty());
    }

    @Test
    void existingContextWithDifferentObservedVolumeIsNotReusedForBinding() {
        acceptedContext(USERS);
        Source source = register("Pictures", "/Users/chris/Pictures");
        probe.contextVolume = "99999999-9999-9999-9999-999999999999";

        assertFailure(SourcePreparationException.Code.EVIDENCE_UNCERTAIN, source.id());
        assertEquals(source, sourceRows.findSourceById(source.id()).orElseThrow());
        assertTrue(periods.findBySourceId(source.id()).isEmpty());
    }

    @Test
    void staleSourceRevisionIsRejectedByBindingGuard() {
        Source source = register("Pictures", "/Users/chris/Pictures");
        probe.beforeRoot = () -> jdbc.update("UPDATE source SET location_revision = location_revision + 1 WHERE id = ?",
                source.id());

        assertThrows(SourceBindingConflictException.class, () -> preparation.prepare(source.id()));
        assertEquals(null, sourceRows.findSourceById(source.id()).orElseThrow().boundLocationContextId());
        assertTrue(periods.findBySourceId(source.id()).isEmpty());
    }

    @Test
    void staleContextRevisionIsRejectedByBindingGuard() {
        LocationContext context = acceptedContext(USERS);
        Source source = register("Pictures", "/Users/chris/Pictures");
        probe.beforeRoot = () -> jdbc.update(
                "UPDATE location_context SET revision = revision + 1 WHERE id = ?", context.id());

        assertThrows(SourceBindingConflictException.class, () -> preparation.prepare(source.id()));
        assertEquals(null, sourceRows.findSourceById(source.id()).orElseThrow().boundLocationContextId());
        assertTrue(periods.findBySourceId(source.id()).isEmpty());
    }

    @Test
    void overlappingSiblingContextCausesConflictWithoutReplacement() {
        LocationContext existing = acceptedContext(unix("/Users/chris/Videos"));
        Source source = register("Pictures", "/Users/chris/Pictures");

        assertFailure(SourcePreparationException.Code.STATE_CHANGED, source.id());

        assertEquals(List.of(existing), contexts.findActive());
        assertEquals(source, sourceRows.findSourceById(source.id()).orElseThrow());
    }

    private Source register(String name, String root) {
        return sources.register(name, root);
    }

    private LocationContext acceptedContext(LocationPath anchor) {
        long now = System.currentTimeMillis();
        LocationContext created = activation.createActive(new LocationContext(
                UUID.randomUUID().toString(), new LocationPathCodec().encode(anchor),
                LocationKeyCodec.encode(anchor).value(), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, now, now));
        return acceptance.acceptReviewRequired(created.id(), 0, now,
                ContinuityProbeResult.accepted(FakeProbe.contextEvidence(anchor)));
    }

    private void assertFailure(SourcePreparationException.Code code, long sourceId) {
        SourcePreparationException failure = assertThrows(SourcePreparationException.class,
                () -> preparation.prepare(sourceId));
        assertEquals(code, failure.code());
    }

    private static LocationPath unix(String path) {
        return LocationPathParser.parse(LocationDialect.UNIX, path);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        @Primary
        FakeProbe sourcePreparationTestProbe() {
            return new FakeProbe();
        }
    }

    static class FakeProbe implements SourcePreparationProbe {
        ContinuityProbeResult<LocationPath> anchorFailure;
        ContinuityProbeResult<MacOsApfsLocationContextEvidence> contextFailure;
        ContinuityProbeResult<MacOsApfsSourceRootEvidence> rootFailure;
        Runnable beforeRoot;
        String contextVolume;
        int contextCalls;

        void reset() {
            anchorFailure = null;
            contextFailure = null;
            rootFailure = null;
            beforeRoot = null;
            contextVolume = DATA_VOLUME;
            contextCalls = 0;
        }

        @Override
        public ContinuityProbeResult<LocationPath> resolveAnchor(LocationPath root) {
            return anchorFailure != null ? anchorFailure : ContinuityProbeResult.accepted(USERS);
        }

        @Override
        public ContinuityProbeResult<MacOsApfsLocationContextEvidence> captureContext(LocationPath anchor) {
            contextCalls++;
            return contextFailure != null ? contextFailure
                    : ContinuityProbeResult.accepted(contextEvidence(anchor, contextVolume));
        }

        @Override
        public ContinuityProbeResult<MacOsApfsSourceRootEvidence> captureRoot(
                MacOsApfsSourceRootProbeRequest request) {
            if (beforeRoot != null) beforeRoot.run();
            if (rootFailure != null) return rootFailure;
            return ContinuityProbeResult.accepted(new MacOsApfsSourceRootEvidence(
                    1, MacOsApfsSourceRootEvidence.PROFILE, 1,
                    request.locationContextId(), request.locationContextRevision(),
                    request.sourceLocationRevision(), request.requestedRoot(),
                    LocationKeyCodec.encode(request.requestedRoot()), DATA_VOLUME, "123456",
                    new MacOsApfsSourceRootEvidence.BirthTime(100, 123456789),
                    true, false, 99));
        }

        static MacOsApfsLocationContextEvidence contextEvidence(LocationPath anchor) {
            return contextEvidence(anchor, DATA_VOLUME);
        }

        static MacOsApfsLocationContextEvidence contextEvidence(LocationPath anchor, String volume) {
            return new MacOsApfsLocationContextEvidence(1,
                    MacOsApfsLocationContextEvidence.PROFILE, 1, anchor,
                    LocationKeyCodec.encode(anchor), "apfs", volume, "2", true, false, 99,
                    MacOsApfsLocationContextEvidence.Diagnostics.empty());
        }
    }
}
