package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementService;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementService;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.catalog.SourceBindingCapture;
import io.github.topher6835.mediacompare.catalog.SourceBindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourceBindingService;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriod;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriodRepository;
import io.github.topher6835.mediacompare.catalog.SourceMembership;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import io.github.topher6835.mediacompare.catalog.SourceRebindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourceRebindingService;
import io.github.topher6835.mediacompare.catalog.SourceRelocationConflictException;
import io.github.topher6835.mediacompare.catalog.SourceRelocationService;
import io.github.topher6835.mediacompare.catalog.SourceUnbindingService;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;
import io.github.topher6835.mediacompare.scan.SourceMembershipPublicationService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import io.github.topher6835.mediacompare.scan.Version3ScanExecutionService;
import io.github.topher6835.mediacompare.scan.authority.ChildStorageBoundary;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;

@SpringBootTest
@Import(SourceBindingServiceTests.ReservationHooks.class)
@DirtiesContext
class SourceBindingServiceTests {
    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("binding.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository sources;
    @Autowired private LocationContextRepository contexts;
    @Autowired private SourceBindingService binding;
    @Autowired private SourceBindingPeriodRepository periods;
    @Autowired private SourceUnbindingService unbinding;
    @Autowired private SourceRebindingService rebinding;
    @Autowired private SourceRelocationService relocation;
    @Autowired private SourceMembershipRepository memberships;
    @Autowired private SourceMembershipPublicationService publisher;
    @Autowired private Version3ScanExecutionService admission;
    @Autowired private ScanRepository scans;
    @Autowired private LocationContextRetirementService retirement;
    @Autowired private LocationContextReplacementService replacement;
    @Autowired private ReservationCoordinator coordinator;

    private final LocationPathCodec pathCodec = new LocationPathCodec();
    private final LocationContextAcceptanceEvidenceCodec acceptanceCodec =
            new LocationContextAcceptanceEvidenceCodec();
    private final SourceBindingEvidenceCodec bindingCodec = new SourceBindingEvidenceCodec();

    @BeforeEach
    @AfterEach
    void clear() {
        coordinator.gate = null;
        coordinator.forceGuardMiss = false;
        coordinator.forceRebindGuardMiss = false;
        coordinator.forceRelocateGuardMiss = false;
        coordinator.forcePeriodInsertFailure = false;
        jdbc.update("DELETE FROM content_hash");
        jdbc.update("DELETE FROM analysis_record");
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM scan_run_source");
        jdbc.update("DELETE FROM job_stage");
        jdbc.update("DELETE FROM job");
        jdbc.update("DELETE FROM scan_run");
        jdbc.update("DELETE FROM content_record");
        jdbc.update("DELETE FROM source_binding_period");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void bindsSourceAtEqualAnchorAndPreservesOtherFields() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive");
        FileEntry file = sources.insert(new FileEntry(null, "UNRESOLVED", null, null,
                null, null, 12, null, null, null, 0, 10, 12));
        Source bound = bind(source, context, capture(source, context, anchor()));

        assertEquals(new Source(source.id(), source.name(), source.rootPath(), key(anchor()),
                5, "unix", context.id(), bound.bindingEvidenceJson(), source.createdAtMs(), 25), bound);
        assertEquals(bound, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
        assertEquals(file, sources.findFileEntryById(file.id()).orElseThrow());
        SourceBindingEvidence envelope = SourceBindingAuthority.requireCurrentBound(bound);
        assertEquals(source.id(), envelope.sourceId());
        assertEquals(5, envelope.macOsApfsSourceRootEvidence().sourceLocationRevision());
        assertEquals(context.id(), envelope.macOsApfsSourceRootEvidence().locationContextId());
        assertEquals(context.revision(), envelope.macOsApfsSourceRootEvidence().locationContextRevision());
        assertEquals(rootEvidence(context.id(), 8, 5, anchor(), VOLUME_UUID, true, false),
                envelope.macOsApfsSourceRootEvidence());
        assertEquals(envelope, bindingCodec.decode(bound.bindingEvidenceJson()));
        assertEquals(java.util.List.of(expectedPeriod(bound)), periods.findBySourceId(source.id()));
    }

    @Test
    void bindsStructuralDescendantUsingPrecapturedTypedResults() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        assertEquals(key(photos()), bound.rootPathKey());
        assertEquals(source.rootPath(), bound.rootPath());
    }

    @Test
    void bindsVisibleSourceUnderLogicalDataVolumeAnchor() {
        LocationPath logicalAnchor = LocationPathParser.parse(LocationDialect.UNIX, "/Users");
        LocationPath logicalRoot = LocationPathParser.parse(LocationDialect.UNIX,
                "/Users/chris/Pictures");
        LocationContext context = acceptedContext(logicalAnchor);
        Source source = legacySource("/Users/chris/Pictures");

        Source bound = bind(source, context, capture(source, context, logicalRoot));

        assertEquals("/Users/chris/Pictures", bound.rootPath());
        assertEquals(logicalAnchor, pathCodec.decode(context.anchorLocationPath()));
        assertEquals(VOLUME_UUID,
                SourceBindingAuthority.requireCurrentBound(bound)
                        .macOsApfsSourceRootEvidence().volumeUuid());
    }

    @Test
    void repeatedBindingConflictsWithoutChangingRows() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        assertFailureUnchanged(SourceBindingConflictException.class, bound, context, 5, 8, 30,
                capture(bound, context, photos(), 6));
        assertEquals(java.util.List.of(expectedPeriod(bound)), periods.findBySourceId(source.id()));
    }

    @Test
    void nonlegacySourceShapesConflict() {
        LocationContext context = acceptedContext(anchor());
        Source dialect = sources.insert(new Source(null, "Dialect", "/Volumes/Archive/Photos",
                "/Volumes/Archive/Photos", 4, "unix", null, null, 10, 12));
        assertFailureUnchanged(SourceBindingConflictException.class, dialect, context, 4, 8, 25,
                capture(dialect, context, photos()));
        Source evidence = sources.insert(new Source(null, "Evidence", "/Volumes/Archive/Other",
                "/Volumes/Archive/Other", 4, null, null, "{}", 10, 12));
        assertFailureUnchanged(SourceBindingConflictException.class, evidence, context, 4, 8, 25,
                capture(evidence, context, otherInside()));
        Source keyMismatch = sources.insert(new Source(null, "Key", "/Volumes/Archive/Third",
                "legacy-other", 4, null, null, null, 10, 12));
        assertFailureUnchanged(SourceBindingConflictException.class, keyMismatch, context, 4, 8, 25,
                capture(keyMismatch, context, thirdInside()));
    }

    @Test
    void invalidConfiguredRootAndExactSpellingMismatchFail() {
        LocationContext context = acceptedContext(anchor());
        Source invalid = legacySource("/Volumes/Archive/Photos/");
        assertFailureUnchanged(IllegalArgumentException.class, invalid, context, 4, 8, 25,
                capture(invalid, context, photos()));

        Source alternate = legacySource("/Volumes/archive/Photos");
        assertFailureUnchanged(IllegalArgumentException.class, alternate, context, 4, 8, 25,
                capture(alternate, context, photos()));
    }

    @Test
    void staleCaptureRevisionsAndTimestampFail() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25,
                new SourceBindingCapture(source.id(), "/Different",
                        ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                        ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                                VOLUME_UUID, true, false))));
        assertFailureUnchanged(SourceBindingConflictException.class, source, context, 3, 8, 25,
                capture(source, context, photos()));
        assertFailureUnchanged(SourceBindingConflictException.class, source, context, 4, 7, 25,
                capture(source, context, photos()));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 11,
                capture(source, context, photos()));
    }

    @Test
    void revisionOverflowAndMissingRowsFail() {
        LocationContext context = acceptedContext(anchor());
        Source overflow = sources.insert(new Source(null, "Overflow", "/Volumes/Archive/Photos",
                "/Volumes/Archive/Photos", Long.MAX_VALUE, 10, 12));
        assertFailureUnchanged(IllegalStateException.class, overflow, context, Long.MAX_VALUE, 8, 25,
                capture(overflow, context, photos(), 5));
        assertThrows(NoSuchElementException.class, () -> binding.bindUnboundSource(999999, 4,
                context.id(), 8, 25, capture(overflow, context, photos())));
        Source source = legacySource("/Volumes/Archive/Other");
        assertThrows(NoSuchElementException.class, () -> binding.bindUnboundSource(source.id(), 4,
                UUID.randomUUID().toString(), 8, 25, capture(source, context, otherInside())));
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
    }

    @Test
    void reviewRetiredAndLegacyAcceptedContextsAreIneligible() {
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationContext review = contexts.insert(context(UUID.randomUUID().toString(), anchor(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 8, null));
        assertFailureUnchanged(SourceBindingConflictException.class, source, review, 4, 8, 25,
                capture(source, review, photos()));
        LocationContext retired = contexts.insert(context(UUID.randomUUID().toString(), otherAnchor(),
                LifecycleStatus.RETIRED, ContinuityStatus.ACCEPTED, 8,
                acceptanceJson(UUID.randomUUID().toString(), 8, otherAnchor())));
        assertFailureUnchanged(SourceBindingConflictException.class, source, retired, 4, 8, 25,
                capture(source, retired, photos()));
        LocationContext legacy = contexts.insert(context(UUID.randomUUID().toString(),
                LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Legacy"),
                LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED, 8,
                new MacOsApfsLocationContextEvidenceCodec().encode(contextEvidence(
                        LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Legacy"),
                        VOLUME_UUID, "2", true, false))));
        assertFailureUnchanged(SourceBindingConflictException.class, source, legacy, 4, 8, 25,
                capture(source, legacy, photos()));
    }

    @Test
    void corruptCurrentAcceptanceEvidenceFailsClosed() {
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationPath[] anchors = {anchor(), otherAnchor(), thirdAnchor(), fourthAnchor()};
        for (int index = 0; index < anchors.length; index++) {
            String id = UUID.randomUUID().toString();
            String evidence = switch (index) {
                case 0 -> "{";
                case 1 -> acceptanceJson(UUID.randomUUID().toString(), 8, anchors[index]);
                case 2 -> acceptanceJson(id, 7, anchors[index]);
                default -> acceptanceJson(id, 8, otherAnchor());
            };
            LocationContext corrupt = contexts.insert(context(id, anchors[index], LifecycleStatus.ACTIVE,
                    ContinuityStatus.ACCEPTED, 8, evidence));
            assertFailureUnchanged(IllegalStateException.class, source, corrupt, 4, 8, 25,
                    capture(source, corrupt, photos()));
        }
    }

    @Test
    void freshContextMustMatchPersistedBaseline() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        SourceBindingCapture unavailable = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.unavailable(),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                        VOLUME_UUID, true, false)));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, unavailable);
        SourceBindingCapture changedInode = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "3", true, false)),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                        VOLUME_UUID, true, false)));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, changedInode);
    }

    @Test
    void sourceRootProvenanceAndClassificationMustMatch() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25,
                new SourceBindingCapture(source.id() + 1, source.rootPath(),
                        ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                        ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                                VOLUME_UUID, true, false))));
        assertInvalidRoot(source, context, rootEvidence(UUID.randomUUID().toString(), 8, 5,
                photos(), VOLUME_UUID, true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 7, 5,
                photos(), VOLUME_UUID, true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 4,
                photos(), VOLUME_UUID, true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                photos(), "22222222-3333-4444-5555-666666666666", true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                photos(), VOLUME_UUID, false, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                photos(), VOLUME_UUID, true, true));
        SourceBindingCapture unavailable = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.unavailable());
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, unavailable);
    }

    @Test
    void siblingRootIsRejectedByStructuralContainment() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Other/Photos");
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                LocationPathParser.parse(LocationDialect.UNIX, source.rootPath()), VOLUME_UUID, true, false));
    }

    @Test
    void boundSourceBlocksRetirementAndReplacement() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        assertThrows(LocationContextRetirementConflictException.class,
                () -> retirement.retireActive(context.id(), 8, 30));
        LocationContext next = replacementContext(anchor());
        assertThrows(LocationContextReplacementConflictException.class,
                () -> replacement.replaceActive(context.id(), 8, 30, next));
        assertEquals(bound, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
        assertTrue(contexts.findById(next.id()).isEmpty());
    }

    @Test
    void guardedUpdateMissIsConflict() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        coordinator.forceGuardMiss = true;
        assertFailureUnchanged(SourceBindingConflictException.class, source, context, 4, 8, 25,
                capture(source, context, photos()));
    }

    @Test
    void periodInsertFailureRollsBackSourceBinding() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        periods.insertOpen(new SourceBindingPeriod(null, source.id(), 1, context.id(), "unix",
                source.rootPath(), "historical-key", "historical-evidence", 10, null, null));

        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> bind(source, context, capture(source, context, photos())));
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(1, periods.findBySourceId(source.id()).size());
    }

    @Test
    void bindingAuthorityRejectsWrongSourceIdAndRevision() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        SourceBindingEvidence envelope = bindingCodec.decode(bound.bindingEvidenceJson());
        assertInvalidBound(bound, new SourceBindingEvidence(1, source.id() + 1,
                envelope.macOsApfsSourceRootEvidence()));
        assertInvalidBound(bound, new SourceBindingEvidence(1, source.id(),
                rootEvidence(context.id(), 8, 4, photos(), VOLUME_UUID, true, false)));
        assertInvalidBound(bound, new SourceBindingEvidence(1, source.id(),
                rootEvidence(UUID.randomUUID().toString(), 8, 5, photos(), VOLUME_UUID, true, false)));
    }

    @Test
    void bindVersusBindAdvancesSourceOnce() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        RaceResults results = race(() -> bind(source, context, capture(source, context, photos())),
                () -> bind(source, context, capture(source, context, photos())));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof SourceBindingConflictException);
        assertEquals(5, sources.findSourceById(source.id()).orElseThrow().locationRevision());
        assertEquals(java.util.List.of(expectedPeriod(sources.findSourceById(source.id()).orElseThrow())),
                periods.findBySourceId(source.id()));
    }

    @Test
    void bindWinsRetirement() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        RaceResults results = race(() -> bind(source, context, capture(source, context, photos())),
                () -> retirement.retireActive(context.id(), 8, 30));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof LocationContextRetirementConflictException);
        assertEquals(LifecycleStatus.ACTIVE, contexts.findById(context.id()).orElseThrow().lifecycleStatus());
        assertEquals(1, periods.findBySourceId(source.id()).size());
    }

    @Test
    void retirementWinsBinding() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        RaceResults results = race(() -> retirement.retireActive(context.id(), 8, 30),
                () -> bind(source, context, capture(source, context, photos())));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof SourceBindingConflictException);
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertTrue(periods.findBySourceId(source.id()).isEmpty());
    }

    @Test
    void bindWinsReplacement() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationContext next = replacementContext(anchor());
        RaceResults results = race(() -> bind(source, context, capture(source, context, photos())),
                () -> replacement.replaceActive(context.id(), 8, 30, next));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        assertTrue(contexts.findById(next.id()).isEmpty());
        assertEquals(1, periods.findBySourceId(source.id()).size());
    }

    @Test
    void replacementWinsBinding() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationContext next = replacementContext(anchor());
        RaceResults results = race(() -> replacement.replaceActive(context.id(), 8, 30, next),
                () -> bind(source, context, capture(source, context, photos())));
        assertEquals(next, results.first());
        assertTrue(results.second() instanceof SourceBindingConflictException);
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(next, contexts.findById(next.id()).orElseThrow());
        assertTrue(periods.findBySourceId(source.id()).isEmpty());
    }

    @Test
    void structuredUnboundSourceRebindsToSameContextWithoutChangingRootOrHistory() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();

        Source rebound = rebind(unbound, context);

        assertEquals(new Source(unbound.id(), unbound.name(), unbound.rootPath(), unbound.rootPathKey(),
                7, unbound.rootPathDialect(), context.id(), rebound.bindingEvidenceJson(),
                unbound.createdAtMs(), 35), rebound);
        assertEquals(rebound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(7, SourceBindingAuthority.requireCurrentBound(rebound)
                .macOsApfsSourceRootEvidence().sourceLocationRevision());
        assertEquals(context.id(), SourceBindingAuthority.requireCurrentBound(rebound)
                .macOsApfsSourceRootEvidence().locationContextId());
        assertEquals(closed, periods.findBySourceId(unbound.id()).getFirst());
        assertEquals(java.util.List.of(closed, expectedPeriod(rebound)), periods.findBySourceId(unbound.id()));
        assertEquals(35, periods.findOpenBySourceId(unbound.id()).orElseThrow().boundAtMs());
        assertThrows(SourceRebindingConflictException.class, () -> rebind(unbound, context));
        assertEquals(2, periods.findBySourceId(unbound.id()).size());
    }

    @Test
    void rebindToAcceptedReplacementContextKeepsOldClosedHistory() {
        LocationContext oldContext = acceptedContext(anchor());
        Source unbound = structuredUnbound(oldContext);
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();
        LocationPath filePath = LocationPathParser.parse(LocationDialect.UNIX,
                "/Volumes/Archive/Photos/a.jpg");
        jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (100, 12, 1)");
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, location_context_id,
                    location_path, location_key, current_content_id, size_bytes,
                    modified_time_epoch_second, modified_time_nano, first_seen_at_ms, last_seen_at_ms)
                VALUES (40, 'RESOLVED', ?, ?, ?, 100, 12, 123, 456, 1, 2)
                """, oldContext.id(), pathCodec.encode(filePath), key(filePath));
        jdbc.update("""
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms)
                VALUES (50, ?, 40, 'a.jpg', 'a.jpg', 'RETIRED', 'PRESENT', 0, 1, 2)
                """, unbound.id());
        retirement.retireActive(oldContext.id(), 8, 31);
        String newId = UUID.randomUUID().toString();
        LocationContext replacementContext = contexts.insert(context(newId, anchor(),
                LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED, 19,
                acceptanceJson(newId, 19, anchor())));

        Source rebound = rebind(unbound, replacementContext);

        assertEquals(replacementContext.id(), rebound.boundLocationContextId());
        assertEquals(closed, periods.findBySourceId(unbound.id()).getFirst());
        assertEquals(oldContext.id(), closed.locationContextId());
        assertEquals(replacementContext.id(), periods.findOpenBySourceId(unbound.id())
                .orElseThrow().locationContextId());
        assertEquals("RETIRED", memberships.findMembershipById(50).orElseThrow().applicabilityStatus());
        assertEquals(100L, sources.findFileEntryById(40).orElseThrow().currentContentId());
        insertScanRunSource(unbound.id(), 30, 7, "DISCOVERING");
        SourceMembership newMember = publisher.publish(
                resolvedCandidate(unbound.id(), 7, replacementContext, filePath), 30, 1, 42);
        assertTrue(newMember.fileEntryId() != 40);
        assertTrue(newMember.id() != 50);
        assertEquals("RETIRED", memberships.findMembershipById(50).orElseThrow().applicabilityStatus());
        assertEquals(100L, sources.findFileEntryById(40).orElseThrow().currentContentId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class));
    }

    @Test
    void neverBoundAndCurrentlyBoundSourcesCannotRebind() {
        LocationContext context = acceptedContext(anchor());
        Source legacy = legacySource("/Volumes/Archive/Photos");
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(legacy.id(), 4, context.id(), 8, 35,
                        capture(legacy, context, photos(), 5)));
        Source bound = bind(legacy, context, capture(legacy, context, photos()));
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(bound.id(), 5, context.id(), 8, 35,
                        capture(bound, context, photos(), 6)));
        assertEquals(bound, sources.findSourceById(bound.id()).orElseThrow());
        assertEquals(1, periods.findBySourceId(bound.id()).size());
        Source unbound = unbinding.unbind(bound.id(), 5, 30);
        java.util.List<SourceBindingPeriod> history = periods.findBySourceId(unbound.id());
        assertThrows(SourceBindingConflictException.class,
                () -> binding.bindUnboundSource(unbound.id(), 6, context.id(), 8, 35,
                        capture(unbound, context, photos(), 7)));
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(history, periods.findBySourceId(unbound.id()));
    }

    @Test
    void missingOrContradictoryClosedHistoryAndActiveMembershipFailIntegrity() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = ?", unbound.id());
        assertRebindFailureUnchanged(IllegalStateException.class, unbound, context,
                capture(unbound, context, photos(), 7));
        periods.insertOpen(new SourceBindingPeriod(null, closed.sourceId(),
                closed.boundSourceLocationRevision(), closed.locationContextId(),
                closed.rootPathDialect(), closed.rootPath(), closed.rootPathKey(),
                closed.bindingEvidenceJson(), closed.boundAtMs(), null, null));
        assertRebindFailureUnchanged(IllegalStateException.class, unbound, context,
                capture(unbound, context, photos(), 7));
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = ?", unbound.id());
        jdbc.update("""
                INSERT INTO source_binding_period (source_id, bound_source_location_revision,
                    location_context_id, root_path_dialect, root_path, root_path_key,
                    binding_evidence_json, bound_at_ms, unbound_source_location_revision, unbound_at_ms)
                VALUES (?, 5, ?, 'unix', ?, 'wrong-key', ?, 25, 6, 30)
                """, unbound.id(), context.id(), unbound.rootPath(), closed.bindingEvidenceJson());
        assertRebindFailureUnchanged(IllegalStateException.class, unbound, context,
                capture(unbound, context, photos(), 7));
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = ?", unbound.id());
        jdbc.update("""
                INSERT INTO source_binding_period (source_id, bound_source_location_revision,
                    location_context_id, root_path_dialect, root_path, root_path_key,
                    binding_evidence_json, bound_at_ms, unbound_source_location_revision, unbound_at_ms)
                VALUES (?, 5, ?, 'unix', ?, ?, ?, 25, 6, 30)
                """, unbound.id(), context.id(), closed.rootPath(), closed.rootPathKey(),
                closed.bindingEvidenceJson());
        jdbc.update("UPDATE source_binding_period SET unbound_at_ms = 31 WHERE source_id = ?", unbound.id());
        assertRebindFailureUnchanged(IllegalStateException.class, unbound, context,
                capture(unbound, context, photos(), 7));
        jdbc.update("UPDATE source_binding_period SET unbound_at_ms = 30 WHERE source_id = ?", unbound.id());
        FileEntry file = sources.insert(new FileEntry(null, "UNRESOLVED", null, null,
                null, null, 12, null, null, null, 0, 10, 12));
        memberships.insert(new SourceMembership(null, unbound.id(), file.id(), "a.jpg", "a.jpg",
                "ACTIVE", "PRESENT", 0, 0, 10, 12, null, null, null, null));
        assertRebindFailureUnchanged(IllegalStateException.class, unbound, context,
                capture(unbound, context, photos(), 7));
    }

    @Test
    void staleRevisionsTimestampAndRevisionOverflowConflict() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingCapture valid = capture(unbound, context, photos(), 7);
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), 5, context.id(), 8, 35, valid));
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 7, 35, valid));
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 8, 29, valid));
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        jdbc.update("UPDATE source SET location_revision = ? WHERE id = ?", Long.MAX_VALUE, unbound.id());
        jdbc.update("UPDATE source_binding_period SET unbound_source_location_revision = ? WHERE source_id = ?",
                Long.MAX_VALUE, unbound.id());
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), Long.MAX_VALUE, context.id(), 8, 35, valid));
        assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
    }

    @Test
    void partialStructuredUnboundStateIsAnIntegrityFailure() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        jdbc.update("UPDATE source SET root_path_dialect = NULL WHERE id = ?", unbound.id());
        assertThrows(IllegalStateException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 8, 35,
                        capture(unbound, context, photos(), 7)));
        assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
    }

    @Test
    void invalidContextAuthorityAndFreshContextCaptureCannotRebind() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingCapture valid = capture(unbound, context, photos(), 7);
        jdbc.update("UPDATE location_context SET continuity_status = 'REVIEW_REQUIRED' WHERE id = ?", context.id());
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 8, 35, valid));
        jdbc.update("UPDATE location_context SET continuity_status = 'ACCEPTED', continuity_evidence_json = ? WHERE id = ?",
                new MacOsApfsLocationContextEvidenceCodec().encode(contextEvidence(anchor(),
                        VOLUME_UUID, "2", true, false)), context.id());
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 8, 35, valid));
        jdbc.update("UPDATE location_context SET continuity_evidence_json = '{' WHERE id = ?", context.id());
        assertThrows(IllegalStateException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 8, 35, valid));
        jdbc.update("UPDATE location_context SET continuity_evidence_json = ? WHERE id = ?",
                context.continuityEvidenceJson(), context.id());
        SourceBindingCapture changedContext = new SourceBindingCapture(unbound.id(), unbound.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "3", true, false)),
                valid.sourceRootProbeResult());
        assertRebindFailureUnchanged(IllegalArgumentException.class, unbound, context, changedContext);
        SourceBindingCapture unavailable = new SourceBindingCapture(unbound.id(), unbound.rootPath(),
                ContinuityProbeResult.unavailable(), valid.sourceRootProbeResult());
        assertRebindFailureUnchanged(IllegalArgumentException.class, unbound, context, unavailable);
        jdbc.update("UPDATE location_context SET lifecycle_status = 'RETIRED', revision = 9 WHERE id = ?",
                context.id());
        assertThrows(SourceRebindingConflictException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 9, 35, valid));
    }

    @Test
    void invalidRootCaptureAndRelocationCannotRebind() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingCapture valid = capture(unbound, context, photos(), 7);
        assertRebindFailureUnchanged(IllegalArgumentException.class, unbound, context,
                new SourceBindingCapture(unbound.id(), unbound.rootPath(),
                        valid.contextProbeResult(), ContinuityProbeResult.unavailable()));
        assertRebindFailureUnchanged(IllegalArgumentException.class, unbound, context,
                capture(unbound, context, photos(), 6));
        assertRebindFailureUnchanged(IllegalArgumentException.class, unbound, context,
                new SourceBindingCapture(unbound.id(), unbound.rootPath(),
                        valid.contextProbeResult(), ContinuityProbeResult.accepted(
                                rootEvidence(context.id(), 8, 7, otherInside(),
                                        VOLUME_UUID, true, false))));
        assertRebindFailureUnchanged(IllegalArgumentException.class, unbound, context,
                new SourceBindingCapture(unbound.id(), "/Volumes/Archive/Other",
                        valid.contextProbeResult(), valid.sourceRootProbeResult()));
        jdbc.update("UPDATE source SET root_path_key = ? WHERE id = ?", key(otherInside()), unbound.id());
        assertThrows(IllegalStateException.class,
                () -> rebinding.rebind(unbound.id(), 6, context.id(), 8, 35, valid));
        assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
    }

    @Test
    void failedPeriodInsertOrGuardedSourceUpdateRollsBackRebind() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();
        coordinator.forcePeriodInsertFailure = true;
        assertThrows(IllegalStateException.class, () -> rebind(unbound, context));
        coordinator.forcePeriodInsertFailure = false;
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(java.util.List.of(closed), periods.findBySourceId(unbound.id()));
        coordinator.forceRebindGuardMiss = true;
        assertThrows(SourceRebindingConflictException.class, () -> rebind(unbound, context));
        coordinator.forceRebindGuardMiss = false;
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(java.util.List.of(closed), periods.findBySourceId(unbound.id()));
    }

    @Test
    void rebindRacesHaveOneWinnerAndSerializeWithContextRetirementAndReplacement() throws Exception {
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> rebind(unbound, context), () -> rebind(unbound, context));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof SourceRebindingConflictException);
            assertEquals(7, sources.findSourceById(unbound.id()).orElseThrow().locationRevision());
            assertEquals(2, periods.findBySourceId(unbound.id()).size());
            assertEquals(1, periods.findBySourceId(unbound.id()).stream()
                    .filter(period -> period.unboundAtMs() == null).count());
        }
        clear();

        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> rebind(unbound, context),
                    () -> retirement.retireActive(context.id(), 8, 40));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof LocationContextRetirementConflictException);
        }
        clear();

        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> retirement.retireActive(context.id(), 8, 40),
                    () -> rebind(unbound, context));
            assertTrue(results.first() instanceof LocationContext);
            assertTrue(results.second() instanceof SourceRebindingConflictException);
            assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
            assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
        }
        clear();

        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            LocationContext next = new LocationContext(UUID.randomUUID().toString(),
                    pathCodec.encode(anchor()), key(anchor()), LifecycleStatus.ACTIVE,
                    ContinuityStatus.REVIEW_REQUIRED, 19, null, 40, 40);
            RaceResults results = race(() -> rebind(unbound, context),
                    () -> replacement.replaceActive(context.id(), 8, 40, next));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        }
        clear();

        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            LocationContext successor = new LocationContext(UUID.randomUUID().toString(),
                    pathCodec.encode(anchor()), key(anchor()), LifecycleStatus.ACTIVE,
                    ContinuityStatus.REVIEW_REQUIRED, 19, null, 40, 40);
            RaceResults results = race(() -> replacement.replaceActive(context.id(), 8, 40, successor),
                    () -> rebind(unbound, context));
            assertEquals(successor, results.first());
            assertTrue(results.second() instanceof SourceRebindingConflictException);
            assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
            assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
        }
    }

    @Test
    void rebindPreservesRetiredMembershipAndArtifactsUntilFreshPositiveReactivatesSameRow() {
        LocationContext context = acceptedContext(anchor());
        Source legacy = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(legacy, context, capture(legacy, context, photos()));
        LocationPath filePath = LocationPathParser.parse(LocationDialect.UNIX,
                "/Volumes/Archive/Photos/a.jpg");
        jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (100, 12, 1)");
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, location_context_id,
                    location_path, location_key, current_content_id, size_bytes,
                    modified_time_epoch_second, modified_time_nano, observation_revision,
                    first_seen_at_ms, last_seen_at_ms)
                VALUES (40, 'RESOLVED', ?, ?, ?, 100, 12, 123, 456, 2, 10, 12)
                """, context.id(), pathCodec.encode(filePath), key(filePath));
        jdbc.update("""
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, membership_revision,
                    observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                    observed_source_location_revision, observed_location_context_revision)
                VALUES (50, ?, 40, 'a.jpg', 'a.jpg', 'ACTIVE', 'MISSING', 2, 2, 10, 12, 5, 8)
                """, bound.id());
        jdbc.update("""
                INSERT INTO analysis_record (id, content_record_id, analysis_type, analyzer_id,
                    analyzer_version, configuration_version, configuration_hash,
                    configuration_json, status, created_at_ms)
                VALUES (60, 100, 'CONTENT_HASH', 'builtin.sha256', '1', 1,
                    'hash', '{}', 'COMPLETED', 1)
                """);
        jdbc.update("INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex) VALUES (60, 'SHA-256', 'abcd')");
        FileEntry fileBefore = sources.findFileEntryById(40).orElseThrow();
        Source unbound = unbinding.unbind(bound.id(), 5, 30);
        SourceMembership retired = memberships.findMembershipById(50).orElseThrow();

        Source rebound = rebind(unbound, context);
        assertEquals("RETIRED", memberships.findMembershipById(50).orElseThrow().applicabilityStatus());
        assertEquals(retired, memberships.findMembershipById(50).orElseThrow());
        assertEquals(fileBefore, sources.findFileEntryById(40).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Integer.class));

        insertScanRunSource(bound.id(), 30, 5, "DISCOVERING");
        var candidate = resolvedCandidate(bound.id(), 5, context, filePath);
        assertThrows(IllegalStateException.class, () -> publisher.publish(candidate, 30, 1, 41));
        jdbc.update("UPDATE scan_run_source SET status = 'DISCOVERED' WHERE id = 30");
        assertThrows(IllegalStateException.class, () -> publisher.reconcile(
                new MissingClaimAuthority(bound.id(), 5, context.id(), 8, photos()),
                scans.findScanRunSourceById(30).orElseThrow(), 41));
        jdbc.update("UPDATE scan_run_source SET source_location_revision = 7, status = 'DISCOVERING' WHERE id = 30");
        SourceMembership active = publisher.publish(resolvedCandidate(bound.id(), 7, context, filePath),
                30, 1, 42);
        assertEquals(retired.id(), active.id());
        assertEquals("ACTIVE", active.applicabilityStatus());
        assertEquals("PRESENT", active.presenceStatus());
        assertEquals(retired.membershipRevision() + 1, active.membershipRevision());
        assertEquals(7L, active.observedSourceLocationRevision());
        assertEquals(8L, active.observedLocationContextRevision());
        assertEquals(fileBefore.currentContentId(), sources.findFileEntryById(40).orElseThrow().currentContentId());
        assertEquals(rebound, sources.findSourceById(bound.id()).orElseThrow());
    }

    @Test
    void oldScanSnapshotCannotGainV3AdmissionAfterRebind() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (80, 'INDEX', 'PENDING', 1, '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation)
                VALUES (81, 80, ?, 'PENDING', 6, 0)
                """, unbound.id());
        rebind(unbound, context);
        assertThrows(Version2ExecutionConflictException.class, () -> admission.create(80));
        jdbc.update("UPDATE scan_run_source SET source_location_revision = 7 WHERE id = 81");
        assertEquals(3, admission.create(80).job().executionVersion());
    }

    @Test
    void relocationChangesOnlyCurrentSourceRootAndOpensNewPeriod() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();

        Source relocated = relocate(unbound, otherInside(), context);

        assertEquals(new Source(unbound.id(), unbound.name(), "/Volumes/Archive/Other", key(otherInside()),
                7, unbound.rootPathDialect(), context.id(), relocated.bindingEvidenceJson(),
                unbound.createdAtMs(), 35), relocated);
        assertEquals(relocated, sources.findSourceById(unbound.id()).orElseThrow());
        var evidence = SourceBindingAuthority.requireCurrentBound(relocated).macOsApfsSourceRootEvidence();
        assertEquals(7, evidence.sourceLocationRevision());
        assertEquals(context.id(), evidence.locationContextId());
        assertEquals(8, evidence.locationContextRevision());
        assertEquals(otherInside(), evidence.rootLocationPath());
        assertEquals(closed, periods.findBySourceId(unbound.id()).getFirst());
        assertEquals(java.util.List.of(closed, expectedPeriod(relocated)), periods.findBySourceId(unbound.id()));
        assertThrows(SourceRelocationConflictException.class,
                () -> relocate(unbound, thirdInside(), context));
        assertThrows(SourceRebindingConflictException.class, () -> rebind(unbound, context));
    }

    @Test
    void relocationRejectsIneligibleStateAndContradictoryHistory() {
        LocationContext context = acceptedContext(anchor());
        Source legacy = legacySource("/Volumes/Archive/Photos");
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(legacy.id(), 4, "/Volumes/Archive/Other",
                        context.id(), 8, 35, relocationCapture(legacy, context, otherInside(), 5)));
        Source bound = bind(legacy, context, capture(legacy, context, photos()));
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(bound.id(), 5, "/Volumes/Archive/Other",
                        context.id(), 8, 35, relocationCapture(bound, context, otherInside(), 6)));
        Source unbound = unbinding.unbind(bound.id(), 5, 30);
        assertRelocationFailureUnchanged(SourceRelocationConflictException.class, unbound,
                context, "/Volumes/Archive/Photos",
                relocationCapture(unbound, context, photos(), 7));
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = ?", unbound.id());
        assertRelocationFailureUnchanged(IllegalStateException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 7));
        periods.insertOpen(new SourceBindingPeriod(null, closed.sourceId(), closed.boundSourceLocationRevision(),
                closed.locationContextId(), closed.rootPathDialect(), closed.rootPath(), closed.rootPathKey(),
                closed.bindingEvidenceJson(), closed.boundAtMs(), null, null));
        assertRelocationFailureUnchanged(IllegalStateException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 7));
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = ?", unbound.id());
        jdbc.update("""
                INSERT INTO source_binding_period (source_id, bound_source_location_revision,
                    location_context_id, root_path_dialect, root_path, root_path_key,
                    binding_evidence_json, bound_at_ms, unbound_source_location_revision, unbound_at_ms)
                VALUES (?, 5, ?, 'unix', ?, 'wrong-key', ?, 25, 6, 30)
                """, unbound.id(), context.id(), unbound.rootPath(), closed.bindingEvidenceJson());
        assertRelocationFailureUnchanged(IllegalStateException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 7));
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = ?", unbound.id());
        jdbc.update("""
                INSERT INTO source_binding_period (source_id, bound_source_location_revision,
                    location_context_id, root_path_dialect, root_path, root_path_key,
                    binding_evidence_json, bound_at_ms, unbound_source_location_revision, unbound_at_ms)
                VALUES (?, 5, ?, 'unix', ?, ?, ?, 25, 6, 31)
                """, unbound.id(), context.id(), unbound.rootPath(), unbound.rootPathKey(),
                closed.bindingEvidenceJson());
        assertRelocationFailureUnchanged(IllegalStateException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 7));
        jdbc.update("UPDATE source_binding_period SET unbound_at_ms = 30 WHERE source_id = ?", unbound.id());
        jdbc.update("UPDATE source_binding_period SET unbound_source_location_revision = 7 WHERE source_id = ?",
                unbound.id());
        assertRelocationFailureUnchanged(IllegalStateException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 7));
        jdbc.update("UPDATE source_binding_period SET unbound_source_location_revision = 6 WHERE source_id = ?",
                unbound.id());
        FileEntry file = sources.insert(new FileEntry(null, "UNRESOLVED", null, null,
                null, null, 12, null, null, null, 0, 10, 12));
        memberships.insert(new SourceMembership(null, unbound.id(), file.id(), "a.jpg", "a.jpg",
                "ACTIVE", "PRESENT", 0, 0, 10, 12, null, null, null, null));
        assertRelocationFailureUnchanged(IllegalStateException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 7));
    }

    @Test
    void relocationRejectsStaleRevisionsTimeOverflowAndMalformedOldRoot() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingCapture valid = relocationCapture(unbound, context, otherInside(), 7);
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), 5, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 7, 35, valid));
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 8, 29, valid));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "relative/root", valid);
        jdbc.update("UPDATE source SET root_path_key = 'wrong-key' WHERE id = ?", unbound.id());
        assertThrows(IllegalStateException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        jdbc.update("UPDATE source SET root_path_key = ? WHERE id = ?", unbound.rootPathKey(), unbound.id());
        jdbc.update("UPDATE source SET root_path_dialect = NULL WHERE id = ?", unbound.id());
        assertThrows(IllegalStateException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        jdbc.update("UPDATE source SET root_path_dialect = 'unix' WHERE id = ?", unbound.id());
        jdbc.update("UPDATE source SET location_revision = ? WHERE id = ?", Long.MAX_VALUE, unbound.id());
        jdbc.update("UPDATE source_binding_period SET unbound_source_location_revision = ? WHERE source_id = ?",
                Long.MAX_VALUE, unbound.id());
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), Long.MAX_VALUE, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
    }

    @Test
    void relocationRequiresCurrentContextAndExactFreshNewRootCapture() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingCapture valid = relocationCapture(unbound, context, otherInside(), 7);
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", new SourceBindingCapture(unbound.id(), "/Volumes/Archive/Other",
                        ContinuityProbeResult.unavailable(), valid.sourceRootProbeResult()));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", new SourceBindingCapture(unbound.id(), "/Volumes/Archive/Other",
                        ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "3", true, false)),
                        valid.sourceRootProbeResult()));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", new SourceBindingCapture(unbound.id(), "/Volumes/Archive/Other",
                        valid.contextProbeResult(), ContinuityProbeResult.unavailable()));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", new SourceBindingCapture(unbound.id() + 1, "/Volumes/Archive/Other",
                        valid.contextProbeResult(), valid.sourceRootProbeResult()));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", new SourceBindingCapture(unbound.id(), unbound.rootPath(),
                        valid.contextProbeResult(), valid.sourceRootProbeResult()));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", new SourceBindingCapture(unbound.id(), "/Volumes/Archive/Other",
                        valid.contextProbeResult(), ContinuityProbeResult.accepted(
                                rootEvidence(context.id(), 8, 7, thirdInside(), VOLUME_UUID, true, false))));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Archive/Other", relocationCapture(unbound, context, otherInside(), 6));
        assertRelocationFailureUnchanged(IllegalArgumentException.class, unbound, context,
                "/Volumes/Other", relocationCapture(unbound, context, otherAnchor(), 7));
        jdbc.update("UPDATE location_context SET continuity_status = 'REVIEW_REQUIRED' WHERE id = ?", context.id());
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        jdbc.update("UPDATE location_context SET continuity_status = 'ACCEPTED', continuity_evidence_json = ? WHERE id = ?",
                new MacOsApfsLocationContextEvidenceCodec().encode(contextEvidence(anchor(),
                        VOLUME_UUID, "2", true, false)), context.id());
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        jdbc.update("UPDATE location_context SET continuity_evidence_json = '{' WHERE id = ?", context.id());
        assertThrows(IllegalStateException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 8, 35, valid));
        jdbc.update("UPDATE location_context SET lifecycle_status = 'RETIRED', revision = 9 WHERE id = ?",
                context.id());
        assertThrows(SourceRelocationConflictException.class,
                () -> relocation.relocateAndBind(unbound.id(), 6, "/Volumes/Archive/Other",
                        context.id(), 9, 35, valid));
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
    }

    @Test
    void relocationWriteAndPeriodFailuresLeaveOldRootUnbound() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();
        coordinator.forceRelocateGuardMiss = true;
        assertThrows(SourceRelocationConflictException.class, () -> relocate(unbound, otherInside(), context));
        coordinator.forceRelocateGuardMiss = false;
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(java.util.List.of(closed), periods.findBySourceId(unbound.id()));
        coordinator.forcePeriodInsertFailure = true;
        assertThrows(IllegalStateException.class, () -> relocate(unbound, otherInside(), context));
        coordinator.forcePeriodInsertFailure = false;
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(java.util.List.of(closed), periods.findBySourceId(unbound.id()));
    }

    @Test
    void relocationPreservesRetiredHistoryAndNewContextObservationCreatesNewFileEntry() {
        LocationContext oldContext = acceptedContext(anchor());
        Source legacy = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(legacy, oldContext, capture(legacy, oldContext, photos()));
        LocationPath oldFile = LocationPathParser.parse(LocationDialect.UNIX,
                "/Volumes/Archive/Photos/a.jpg");
        jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (100, 12, 1)");
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, location_context_id,
                    location_path, location_key, current_content_id, size_bytes,
                    modified_time_epoch_second, modified_time_nano, observation_revision,
                    first_seen_at_ms, last_seen_at_ms)
                VALUES (40, 'RESOLVED', ?, ?, ?, 100, 12, 123, 456, 2, 10, 12)
                """, oldContext.id(), pathCodec.encode(oldFile), key(oldFile));
        jdbc.update("""
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, membership_revision,
                    observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                    observed_source_location_revision, observed_location_context_revision)
                VALUES (50, ?, 40, 'a.jpg', 'a.jpg', 'ACTIVE', 'PRESENT', 2, 2, 10, 12, 5, 8)
                """, bound.id());
        jdbc.update("""
                INSERT INTO analysis_record (id, content_record_id, analysis_type, analyzer_id,
                    analyzer_version, configuration_version, configuration_hash,
                    configuration_json, status, created_at_ms)
                VALUES (60, 100, 'CONTENT_HASH', 'builtin.sha256', '1', 1,
                    'hash', '{}', 'COMPLETED', 1)
                """);
        jdbc.update("INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex) VALUES (60, 'SHA-256', 'abcd')");
        Source unbound = unbinding.unbind(bound.id(), 5, 30);
        SourceMembership retired = memberships.findMembershipById(50).orElseThrow();
        FileEntry oldFileEntry = sources.findFileEntryById(40).orElseThrow();
        SourceBindingPeriod closed = periods.findLatestBySourceId(unbound.id()).orElseThrow();
        LocationContext newContext = acceptedContext(otherAnchor());
        LocationPath newRoot = LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Other/Photos");
        LocationPath newFile = LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Other/Photos/a.jpg");

        Source relocated = relocate(unbound, newRoot, newContext);

        assertEquals(newContext.id(), relocated.boundLocationContextId());
        assertEquals(closed, periods.findBySourceId(unbound.id()).getFirst());
        assertEquals(retired, memberships.findMembershipById(50).orElseThrow());
        assertEquals(oldFileEntry, sources.findFileEntryById(40).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Integer.class));

        insertScanRunSource(unbound.id(), 30, 6, "DISCOVERING");
        assertThrows(IllegalStateException.class,
                () -> publisher.publish(resolvedCandidate(unbound.id(), 5, oldContext, oldFile), 30, 1, 41));
        jdbc.update("UPDATE scan_run_source SET status = 'DISCOVERED' WHERE id = 30");
        assertThrows(IllegalStateException.class, () -> publisher.reconcile(
                new MissingClaimAuthority(unbound.id(), 5, oldContext.id(), 8, photos()),
                scans.findScanRunSourceById(30).orElseThrow(), 41));
        jdbc.update("UPDATE scan_run_source SET source_location_revision = 7, status = 'DISCOVERING' WHERE id = 30");
        SourceMembership current = publisher.publish(resolvedCandidate(unbound.id(), 7, newContext, newFile),
                30, 1, 42);
        assertTrue(current.id() != retired.id());
        assertTrue(current.fileEntryId() != oldFileEntry.id());
        assertEquals(7L, current.observedSourceLocationRevision());
        assertEquals(newContext.revision(), current.observedLocationContextRevision());
        assertEquals(retired, memberships.findMembershipById(50).orElseThrow());
        assertEquals(oldFileEntry, sources.findFileEntryById(40).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Integer.class));
    }

    @Test
    void oldScanSnapshotCannotGainV3AuthorityAfterRelocation() {
        LocationContext context = acceptedContext(anchor());
        Source unbound = structuredUnbound(context);
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (80, 'INDEX', 'PENDING', 1, '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation)
                VALUES (81, 80, ?, 'PENDING', 6, 0)
                """, unbound.id());
        relocate(unbound, otherInside(), context);
        assertThrows(Version2ExecutionConflictException.class, () -> admission.create(80));
        jdbc.update("UPDATE scan_run_source SET source_location_revision = 7 WHERE id = 81");
        assertEquals(3, admission.create(80).job().executionVersion());
    }

    @Test
    void relocationRacesSerializeWithOtherLifecycleTransitions() throws Exception {
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> relocate(unbound, otherInside(), context),
                    () -> relocate(unbound, thirdInside(), context));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof SourceRelocationConflictException);
            assertEquals("/Volumes/Archive/Other", sources.findSourceById(unbound.id()).orElseThrow().rootPath());
            assertEquals(2, periods.findBySourceId(unbound.id()).size());
            assertEquals(1, periods.findBySourceId(unbound.id()).stream()
                    .filter(period -> period.unboundAtMs() == null).count());
        }
        clear();
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> rebind(unbound, context),
                    () -> relocate(unbound, otherInside(), context));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof SourceRelocationConflictException);
            assertEquals(unbound.rootPath(), sources.findSourceById(unbound.id()).orElseThrow().rootPath());
            assertEquals(2, periods.findBySourceId(unbound.id()).size());
        }
        clear();
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> relocate(unbound, otherInside(), context),
                    () -> rebind(unbound, context));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof SourceRebindingConflictException);
            assertEquals("/Volumes/Archive/Other", sources.findSourceById(unbound.id()).orElseThrow().rootPath());
            assertEquals(2, periods.findBySourceId(unbound.id()).size());
        }
        clear();
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> relocate(unbound, otherInside(), context),
                    () -> retirement.retireActive(context.id(), 8, 40));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof LocationContextRetirementConflictException);
        }
        clear();
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            RaceResults results = race(() -> retirement.retireActive(context.id(), 8, 40),
                    () -> relocate(unbound, otherInside(), context));
            assertTrue(results.first() instanceof LocationContext);
            assertTrue(results.second() instanceof SourceRelocationConflictException);
            assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
            assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
        }
        clear();
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            LocationContext next = new LocationContext(UUID.randomUUID().toString(),
                    pathCodec.encode(anchor()), key(anchor()), LifecycleStatus.ACTIVE,
                    ContinuityStatus.REVIEW_REQUIRED, 19, null, 40, 40);
            RaceResults results = race(() -> relocate(unbound, otherInside(), context),
                    () -> replacement.replaceActive(context.id(), 8, 40, next));
            assertTrue(results.first() instanceof Source);
            assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        }
        clear();
        {
            LocationContext context = acceptedContext(anchor());
            Source unbound = structuredUnbound(context);
            LocationContext next = new LocationContext(UUID.randomUUID().toString(),
                    pathCodec.encode(anchor()), key(anchor()), LifecycleStatus.ACTIVE,
                    ContinuityStatus.REVIEW_REQUIRED, 19, null, 40, 40);
            RaceResults results = race(() -> replacement.replaceActive(context.id(), 8, 40, next),
                    () -> relocate(unbound, otherInside(), context));
            assertEquals(next, results.first());
            assertTrue(results.second() instanceof SourceRelocationConflictException);
            assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
            assertTrue(periods.findOpenBySourceId(unbound.id()).isEmpty());
            jdbc.update("UPDATE location_context SET continuity_status = 'ACCEPTED', continuity_evidence_json = ? WHERE id = ?",
                    acceptanceJson(next.id(), next.revision(), anchor()), next.id());
            assertEquals("/Volumes/Archive/Other", relocate(unbound, otherInside(),
                    contexts.findById(next.id()).orElseThrow()).rootPath());
        }
    }

    private Source relocate(Source unbound, LocationPath newRoot, LocationContext context) {
        String newRootText = unixPath(newRoot);
        return relocation.relocateAndBind(unbound.id(), 6, newRootText, context.id(),
                context.revision(), 35, relocationCapture(unbound, context, newRoot, 7));
    }

    private SourceBindingCapture relocationCapture(Source source, LocationContext context,
            LocationPath newRoot, long rootRevision) {
        return new SourceBindingCapture(source.id(), unixPath(newRoot),
                ContinuityProbeResult.accepted(contextEvidence(
                        pathCodec.decode(context.anchorLocationPath()), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), context.revision(),
                        rootRevision, newRoot, VOLUME_UUID, true, false)));
    }

    private <T extends Throwable> void assertRelocationFailureUnchanged(Class<T> expected, Source unbound,
            LocationContext context, String requestedRoot, SourceBindingCapture capture) {
        java.util.List<SourceBindingPeriod> history = periods.findBySourceId(unbound.id());
        assertThrows(expected, () -> relocation.relocateAndBind(unbound.id(), 6, requestedRoot,
                context.id(), context.revision(), 35, capture));
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(history, periods.findBySourceId(unbound.id()));
    }

    private static String unixPath(LocationPath path) {
        return path.components().isEmpty() ? "/" : "/" + String.join("/", path.components());
    }

    private Source structuredUnbound(LocationContext context) {
        Source legacy = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(legacy, context, capture(legacy, context, photos()));
        return unbinding.unbind(bound.id(), 5, 30);
    }

    private Source rebind(Source unbound, LocationContext context) {
        return rebinding.rebind(unbound.id(), 6, context.id(), context.revision(), 35,
                capture(unbound, context, photos(), 7));
    }

    private <T extends Throwable> void assertRebindFailureUnchanged(Class<T> expected, Source unbound,
            LocationContext context, SourceBindingCapture capture) {
        java.util.List<SourceBindingPeriod> history = periods.findBySourceId(unbound.id());
        assertThrows(expected, () -> rebinding.rebind(unbound.id(), 6, context.id(),
                context.revision(), 35, capture));
        assertEquals(unbound, sources.findSourceById(unbound.id()).orElseThrow());
        assertEquals(history, periods.findBySourceId(unbound.id()));
    }

    private void insertScanRunSource(long sourceId, long id, long revision, String status) {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version, options_json,
                    created_at_ms, started_at_ms)
                VALUES (?, 'INDEX', 'RUNNING', 1, '{}', 1, 2)
                """, id);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation, started_at_ms)
                VALUES (?, ?, ?, ?, ?, 1, 2)
                """, id, id, sourceId, status, revision);
    }

    private ResolvedFileCandidate resolvedCandidate(long sourceId, long sourceRevision,
            LocationContext context, LocationPath file) {
        return new ResolvedFileCandidate(sourceId, sourceRevision, context.id(), context.revision(),
                file, LocationKeyCodec.encode(file), "a.jpg", "a.jpg", "apfs", VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false, 12, 123, 456);
    }

    private RaceResults race(Attempt firstAttempt, Attempt secondAttempt) throws Exception {
        ReservationGate gate = new ReservationGate();
        coordinator.gate = gate;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> attempt(firstAttempt));
            try {
                assertTrue(gate.firstReserved.await(10, TimeUnit.SECONDS));
                var second = callers.submit(() -> attempt(secondAttempt));
                assertTrue(gate.secondEntered.await(10, TimeUnit.SECONDS));
                assertFalse(gate.secondReserved.await(500, TimeUnit.MILLISECONDS));
                assertEquals(1, gate.secondAuthorityRead.getCount());
                gate.releaseFirst.countDown();
                Object firstResult = first.get(15, TimeUnit.SECONDS);
                Object secondResult = second.get(15, TimeUnit.SECONDS);
                assertEquals(0, gate.secondReserved.getCount());
                assertEquals(0, gate.secondAuthorityRead.getCount());
                return new RaceResults(firstResult, secondResult);
            } finally {
                gate.releaseFirst.countDown();
                coordinator.gate = null;
            }
        }
    }

    private static Object attempt(Attempt attempt) {
        try {
            return attempt.run();
        } catch (SourceBindingConflictException | LocationContextRetirementConflictException
                | LocationContextReplacementConflictException | SourceRebindingConflictException
                | SourceRelocationConflictException exception) {
            return exception;
        }
    }

    private <T extends Throwable> void assertFailureUnchanged(Class<T> expected, Source source,
            LocationContext context, long sourceRevision, long contextRevision, long time, SourceBindingCapture capture) {
        assertThrows(expected, () -> binding.bindUnboundSource(
                source.id(), sourceRevision, context.id(), contextRevision, time, capture));
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
        assertEquals(source.boundLocationContextId() == null ? 0 : 1,
                periods.findBySourceId(source.id()).size());
    }

    private SourceBindingPeriod expectedPeriod(Source bound) {
        SourceBindingPeriod persisted = periods.findOpenBySourceId(bound.id()).orElseThrow();
        return new SourceBindingPeriod(persisted.id(), bound.id(), bound.locationRevision(),
                bound.boundLocationContextId(), bound.rootPathDialect(), bound.rootPath(),
                bound.rootPathKey(), bound.bindingEvidenceJson(), bound.updatedAtMs(), null, null);
    }

    private void assertInvalidRoot(Source source, LocationContext context, MacOsApfsSourceRootEvidence root) {
        SourceBindingCapture capture = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.accepted(root));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, capture);
    }

    private void assertInvalidBound(Source bound, SourceBindingEvidence evidence) {
        Source altered = new Source(bound.id(), bound.name(), bound.rootPath(), bound.rootPathKey(),
                bound.locationRevision(), bound.rootPathDialect(), bound.boundLocationContextId(),
                bindingCodec.encode(evidence), bound.createdAtMs(), bound.updatedAtMs());
        assertThrows(IllegalStateException.class, () -> SourceBindingAuthority.requireCurrentBound(altered));
    }

    private Source bind(Source source, LocationContext context, SourceBindingCapture capture) {
        return binding.bindUnboundSource(source.id(), 4, context.id(), 8, 25, capture);
    }

    private SourceBindingCapture capture(Source source, LocationContext context, LocationPath root) {
        return capture(source, context, root, 5);
    }

    private SourceBindingCapture capture(Source source, LocationContext context, LocationPath root, long rootRevision) {
        return new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(
                        pathCodec.decode(context.anchorLocationPath()), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), context.revision(),
                        rootRevision, root, VOLUME_UUID, true, false)));
    }

    private LocationContext acceptedContext(LocationPath anchor) {
        String id = UUID.randomUUID().toString();
        return contexts.insert(context(id, anchor, LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED,
                8, acceptanceJson(id, 8, anchor)));
    }

    private LocationContext replacementContext(LocationPath anchor) {
        return new LocationContext(UUID.randomUUID().toString(), pathCodec.encode(anchor), key(anchor),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 19, null, 30, 30);
    }

    private LocationContext context(String id, LocationPath anchor, LifecycleStatus lifecycle,
            ContinuityStatus status, long revision, String evidence) {
        return new LocationContext(id, pathCodec.encode(anchor), key(anchor), lifecycle, status,
                revision, evidence, 10, 20);
    }

    private String acceptanceJson(String id, long revision, LocationPath anchor) {
        return acceptanceCodec.encode(new LocationContextAcceptanceEvidence(1, id, revision,
                contextEvidence(anchor, VOLUME_UUID, "2", true, false)));
    }

    private Source legacySource(String root) {
        return sources.insert(new Source(null, "Photos", root, root, 4, 10, 12));
    }

    private static MacOsApfsLocationContextEvidence contextEvidence(LocationPath anchor,
            String volume, String inode, boolean directory, boolean link) {
        return new MacOsApfsLocationContextEvidence(1, "macos-local-apfs", 1, anchor,
                LocationKeyCodec.encode(anchor), "apfs", volume, inode, directory, link, 99,
                MacOsApfsLocationContextEvidence.Diagnostics.empty());
    }

    private static MacOsApfsSourceRootEvidence rootEvidence(String contextId, long contextRevision,
            long sourceRevision, LocationPath root, String volume, boolean directory, boolean link) {
        return new MacOsApfsSourceRootEvidence(1, "macos-local-apfs-source-root", 1,
                contextId, contextRevision, sourceRevision, root, LocationKeyCodec.encode(root),
                volume, "123456", new MacOsApfsSourceRootEvidence.BirthTime(100, 123456789),
                directory, link, 99);
    }

    private static LocationPath anchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive");
    }

    private static LocationPath photos() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Photos");
    }

    private static LocationPath otherInside() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Other");
    }

    private static LocationPath thirdInside() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Third");
    }

    private static LocationPath otherAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Other");
    }

    private static LocationPath thirdAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Third");
    }

    private static LocationPath fourthAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Fourth");
    }

    private static String key(LocationPath path) {
        return LocationKeyCodec.encode(path).value();
    }

    private static final String VOLUME_UUID = "11111111-2222-3333-4444-555555555555";

    @FunctionalInterface
    private interface Attempt {
        Object run();
    }

    private record RaceResults(Object first, Object second) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReservationHooks {
        @Bean
        ReservationCoordinator reservationCoordinator() {
            return new ReservationCoordinator();
        }

        @Bean
        @Primary
        CoordinatedLocationContextRepository coordinatedLocationContextRepository(
                JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            return new CoordinatedLocationContextRepository(jdbc, coordinator);
        }

        @Bean
        @Primary
        CoordinatedCatalogRepository coordinatedCatalogRepository(JdbcTemplate jdbc,
                ReservationCoordinator coordinator) {
            return new CoordinatedCatalogRepository(jdbc, coordinator);
        }

        @Bean
        @Primary
        CoordinatedPeriodRepository coordinatedPeriodRepository(JdbcTemplate jdbc,
                ReservationCoordinator coordinator) {
            return new CoordinatedPeriodRepository(jdbc, coordinator);
        }
    }

    static class ReservationCoordinator {
        volatile ReservationGate gate;
        volatile boolean forceGuardMiss;
        volatile boolean forceRebindGuardMiss;
        volatile boolean forceRelocateGuardMiss;
        volatile boolean forcePeriodInsertFailure;
    }

    static class CoordinatedLocationContextRepository extends LocationContextRepository {
        private final ReservationCoordinator coordinator;

        CoordinatedLocationContextRepository(JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            super(jdbc);
            this.coordinator = coordinator;
        }

        @Override
        public void reserveWrite() {
            ReservationGate gate = coordinator.gate;
            if (gate == null) {
                super.reserveWrite();
                return;
            }
            if (gate.firstCaller.compareAndSet(null, Thread.currentThread())) {
                super.reserveWrite();
                gate.firstReserved.countDown();
                try {
                    if (!gate.releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("First writer was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding first writer", exception);
                }
            } else {
                gate.secondCaller.set(Thread.currentThread());
                gate.secondEntered.countDown();
                super.reserveWrite();
                gate.secondReserved.countDown();
            }
        }

        @Override
        public Optional<LocationContext> findById(String id) {
            markAuthorityRead(coordinator.gate);
            return super.findById(id);
        }
    }

    static class CoordinatedCatalogRepository extends CatalogRepository {
        private final ReservationCoordinator coordinator;

        CoordinatedCatalogRepository(JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            super(jdbc);
            this.coordinator = coordinator;
        }

        @Override
        public Optional<Source> findSourceById(long id) {
            markAuthorityRead(coordinator.gate);
            return super.findSourceById(id);
        }

        @Override
        public int bindUnboundSource(long sourceId, long revision, String configuredRootPath,
                String rootLocationKey, String contextId, String evidenceJson, long boundAtMs) {
            if (coordinator.forceGuardMiss) {
                return 0;
            }
            return super.bindUnboundSource(sourceId, revision, configuredRootPath,
                    rootLocationKey, contextId, evidenceJson, boundAtMs);
        }

        @Override
        public int rebindStructuredSource(Source source, String contextId,
                String evidenceJson, long reboundAtMs) {
            if (coordinator.forceRebindGuardMiss) {
                return 0;
            }
            return super.rebindStructuredSource(source, contextId, evidenceJson, reboundAtMs);
        }

        @Override
        public int relocateAndBindStructuredSource(Source source, String newRootPath, String newRootKey,
                String contextId, String evidenceJson, long relocatedAtMs) {
            if (coordinator.forceRelocateGuardMiss) {
                return 0;
            }
            return super.relocateAndBindStructuredSource(source, newRootPath, newRootKey,
                    contextId, evidenceJson, relocatedAtMs);
        }
    }

    static class CoordinatedPeriodRepository extends SourceBindingPeriodRepository {
        private final ReservationCoordinator coordinator;

        CoordinatedPeriodRepository(JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            super(jdbc);
            this.coordinator = coordinator;
        }

        @Override
        public SourceBindingPeriod insertOpen(SourceBindingPeriod period) {
            if (coordinator.forcePeriodInsertFailure) {
                throw new IllegalStateException("Period insert failed");
            }
            return super.insertOpen(period);
        }
    }

    private static void markAuthorityRead(ReservationGate gate) {
        if (gate != null && Thread.currentThread() == gate.secondCaller.get()) {
            gate.secondAuthorityRead.countDown();
        }
    }

    static class ReservationGate {
        final AtomicReference<Thread> firstCaller = new AtomicReference<>();
        final AtomicReference<Thread> secondCaller = new AtomicReference<>();
        final CountDownLatch firstReserved = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondReserved = new CountDownLatch(1);
        final CountDownLatch secondAuthorityRead = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
    }
}
