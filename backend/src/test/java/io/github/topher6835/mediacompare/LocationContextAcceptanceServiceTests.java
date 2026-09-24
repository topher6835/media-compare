package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceService;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementService;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementService;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.ContinuityReason;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;

@SpringBootTest
@Import(LocationContextAcceptanceServiceTests.ReservationHooks.class)
@DirtiesContext
class LocationContextAcceptanceServiceTests {
    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("acceptance.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository catalog;
    @Autowired private LocationContextRepository contexts;
    @Autowired private LocationContextAcceptanceService acceptance;
    @Autowired private LocationContextRetirementService retirement;
    @Autowired private LocationContextReplacementService replacement;
    @Autowired private ReservationCoordinator coordinator;

    private final LocationPathCodec pathCodec = new LocationPathCodec();
    private final LocationContextAcceptanceEvidenceCodec envelopeCodec =
            new LocationContextAcceptanceEvidenceCodec();

    @BeforeEach
    @AfterEach
    void clear() {
        coordinator.gate = null;
        coordinator.forceGuardMiss = false;
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void acceptsWithPostRevisionEnvelopeAndPreservesOtherFields() {
        LocationContext old = active();
        MacOsApfsLocationContextEvidence evidence = evidence(anchor(), true, false);

        LocationContext accepted = acceptance.acceptReviewRequired(old.id(), 7, 25,
                ContinuityProbeResult.accepted(evidence));
        assertEquals(context(old.id(), anchor(), LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED,
                8, accepted.continuityEvidenceJson(), 11, 25), accepted);
        assertEquals(accepted, contexts.findById(old.id()).orElseThrow());
        LocationContextAcceptanceEvidence envelope = LocationContextAcceptanceAuthority.requireCurrentAccepted(accepted);
        assertEquals(1, envelope.version());
        assertEquals(old.id(), envelope.contextId());
        assertEquals(accepted.revision(), envelope.contextRevision());
        assertEquals(evidence, envelope.macOsApfsEvidence());
        assertEquals(envelope, envelopeCodec.decode(accepted.continuityEvidenceJson()));
    }

    @Test
    void missingContextAndInvalidArgumentsLeaveNoMutation() {
        LocationContext old = active();
        assertThrows(NoSuchElementException.class, () -> acceptance.acceptReviewRequired(
                UUID.randomUUID().toString(), 7, 25, acceptedProbe()));
        assertThrows(IllegalArgumentException.class, () -> acceptance.acceptReviewRequired("INVALID", 7, 25, acceptedProbe()));
        assertThrows(IllegalArgumentException.class, () -> acceptance.acceptReviewRequired(old.id(), -1, 25, acceptedProbe()));
        assertThrows(IllegalArgumentException.class, () -> acceptance.acceptReviewRequired(old.id(), 7, -1, acceptedProbe()));
        assertThrows(IllegalArgumentException.class, () -> acceptance.acceptReviewRequired(old.id(), 7, 25, null));
        assertEquals(old, contexts.findById(old.id()).orElseThrow());
    }

    @Test
    void alreadyAcceptedAndLegacyRawAcceptedAreConflicts() {
        LocationContext old = active();
        LocationContext accepted = acceptance.acceptReviewRequired(old.id(), 7, 25, acceptedProbe());
        assertConflictUnchanged(accepted, 8);

        LocationContext legacy = contexts.insert(context(UUID.randomUUID().toString(), otherAnchor(),
                LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED, 4,
                new MacOsApfsLocationContextEvidenceCodec().encode(evidence(otherAnchor(), true, false)), 11, 15));
        assertConflictUnchanged(legacy, 4);
        assertThrows(IllegalStateException.class, () -> LocationContextAcceptanceAuthority.requireCurrentAccepted(legacy));
    }

    @Test
    void retiredAndStaleContextsConflict() {
        LocationContext retired = contexts.insert(context(UUID.randomUUID().toString(), anchor(),
                LifecycleStatus.RETIRED, ContinuityStatus.REVIEW_REQUIRED, 7, null, 11, 15));
        assertConflictUnchanged(retired, 7);
        LocationContext active = active();
        assertConflictUnchanged(active, 6);
    }

    @Test
    void timestampRegressionOverflowAndCorruptAnchorFailWithoutMutation() {
        LocationContext old = active();
        assertFailureUnchanged(IllegalArgumentException.class, old, 7, 14, acceptedProbe());

        LocationContext overflow = contexts.insert(context(UUID.randomUUID().toString(), otherAnchor(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, Long.MAX_VALUE, null, 11, 15));
        assertFailureUnchanged(IllegalStateException.class, overflow, Long.MAX_VALUE, 25,
                ContinuityProbeResult.accepted(evidence(otherAnchor(), true, false)));

        LocationContext corrupt = contexts.insert(new LocationContext(UUID.randomUUID().toString(), "not-json",
                key(LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Corrupt")),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 7, null, 11, 15));
        assertFailureUnchanged(IllegalStateException.class, corrupt, 7, 25, acceptedProbe());
    }

    @Test
    void rejectedProbeOutcomesAndUnusableEvidenceLeaveRowUnchanged() {
        LocationContext old = active();
        List<ContinuityProbeResult<MacOsApfsLocationContextEvidence>> failures = List.of(
                ContinuityProbeResult.unavailable(),
                ContinuityProbeResult.mismatch(ContinuityReason.ANCHOR_LOCATION_MISMATCH),
                ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN),
                ContinuityProbeResult.unsupported(),
                ContinuityProbeResult.error());
        for (var failure : failures) {
            assertFailureUnchanged(IllegalArgumentException.class, old, 7, 25, failure);
        }
        assertFailureUnchanged(IllegalArgumentException.class, old, 7, 25,
                ContinuityProbeResult.accepted(evidence(otherAnchor(), true, false)));
        assertFailureUnchanged(IllegalArgumentException.class, old, 7, 25,
                ContinuityProbeResult.accepted(evidence(anchor(), false, false)));
        assertFailureUnchanged(IllegalArgumentException.class, old, 7, 25,
                ContinuityProbeResult.accepted(evidence(anchor(), true, true)));
        assertThrows(IllegalArgumentException.class, () -> new ContinuityProbeResult<MacOsApfsLocationContextEvidence>(
                ContinuityOutcome.ACCEPTED, ContinuityReason.EVIDENCE_MATCHED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new MacOsApfsLocationContextEvidence(
                1, "unsupported", 1, anchor(), LocationKeyCodec.encode(anchor()), "apfs",
                "11111111-2222-3333-4444-555555555555", "2", true, false, 99,
                MacOsApfsLocationContextEvidence.Diagnostics.empty()));
        assertEquals(old, contexts.findById(old.id()).orElseThrow());
    }

    @Test
    void boundSourceBlocksAcceptanceWithoutMutation() {
        LocationContext old = active();
        Source source = catalog.insert(new Source(null, "Bound", "/Volumes/Archive", "/Volumes/Archive",
                4, "UNIX", old.id(), "{\"bindingVersion\":1}", 10, 12));
        assertConflictUnchanged(old, 7);
        assertEquals(source, catalog.findSourceById(source.id()).orElseThrow());
    }

    @Test
    void guardedUpdateMissIsAcceptanceConflict() {
        LocationContext old = active();
        coordinator.forceGuardMiss = true;
        assertConflictUnchanged(old, 7);
    }

    @Test
    void currentAuthorityRejectsWrongIdRevisionAndAnchor() {
        LocationContext old = active();
        LocationContext accepted = acceptance.acceptReviewRequired(old.id(), 7, 25, acceptedProbe());
        LocationContextAcceptanceEvidence actual = envelopeCodec.decode(accepted.continuityEvidenceJson());
        assertInvalidCurrent(accepted, new LocationContextAcceptanceEvidence(1,
                UUID.randomUUID().toString(), 8, actual.macOsApfsEvidence()));
        assertInvalidCurrent(accepted, new LocationContextAcceptanceEvidence(1,
                old.id(), 7, actual.macOsApfsEvidence()));
        assertInvalidCurrent(accepted, new LocationContextAcceptanceEvidence(1,
                old.id(), 8, evidence(otherAnchor(), true, false)));
        assertEquals(accepted, contexts.findById(old.id()).orElseThrow());
    }

    @Test
    void retiredHistoricalEvidenceRetainsAcceptanceRevision() {
        LocationContext old = active();
        LocationContext accepted = acceptance.acceptReviewRequired(old.id(), 7, 25, acceptedProbe());
        LocationContext retired = retirement.retireActive(old.id(), 8, 30);
        assertEquals(9, retired.revision());
        assertEquals(accepted.continuityEvidenceJson(), retired.continuityEvidenceJson());
        assertEquals(8, envelopeCodec.decode(retired.continuityEvidenceJson()).contextRevision());
        assertThrows(IllegalArgumentException.class, () -> LocationContextAcceptanceAuthority.requireCurrentAccepted(retired));
    }

    @Test
    void replacementPreservesHistoricalEvidenceAndCreatesReviewRequiredRow() {
        LocationContext old = active();
        LocationContext accepted = acceptance.acceptReviewRequired(old.id(), 7, 25, acceptedProbe());
        LocationContext next = context(UUID.randomUUID().toString(), anchor(), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 19, null, 30, 30);
        replacement.replaceActive(old.id(), 8, 30, next);
        LocationContext retired = contexts.findById(old.id()).orElseThrow();
        assertEquals(9, retired.revision());
        assertEquals(accepted.continuityEvidenceJson(), retired.continuityEvidenceJson());
        assertEquals(next, contexts.findById(next.id()).orElseThrow());
    }

    @Test
    void concurrentAcceptancesAdvanceRevisionOnlyOnce() throws Exception {
        LocationContext old = active();
        RaceResults results = race(() -> accept(old), () -> accept(old));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof LocationContextAcceptanceConflictException);
        assertEquals(8, contexts.findById(old.id()).orElseThrow().revision());
    }

    @Test
    void acceptanceWinsRetirement() throws Exception {
        LocationContext old = active();
        RaceResults results = race(() -> accept(old), () -> retirement.retireActive(old.id(), 7, 30));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof LocationContextRetirementConflictException);
        assertEquals(ContinuityStatus.ACCEPTED, contexts.findById(old.id()).orElseThrow().continuityStatus());
    }

    @Test
    void retirementWinsAcceptance() throws Exception {
        LocationContext old = active();
        RaceResults results = race(() -> retirement.retireActive(old.id(), 7, 30), () -> accept(old));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof LocationContextAcceptanceConflictException);
        assertEquals(LifecycleStatus.RETIRED, contexts.findById(old.id()).orElseThrow().lifecycleStatus());
    }

    @Test
    void acceptanceWinsReplacement() throws Exception {
        LocationContext old = active();
        LocationContext next = next();
        RaceResults results = race(() -> accept(old), () -> replacement.replaceActive(old.id(), 7, 30, next));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        assertTrue(contexts.findById(next.id()).isEmpty());
    }

    @Test
    void replacementWinsAcceptance() throws Exception {
        LocationContext old = active();
        LocationContext next = next();
        RaceResults results = race(() -> replacement.replaceActive(old.id(), 7, 30, next), () -> accept(old));
        assertEquals(next, results.first());
        assertTrue(results.second() instanceof LocationContextAcceptanceConflictException);
        assertEquals(LifecycleStatus.RETIRED, contexts.findById(old.id()).orElseThrow().lifecycleStatus());
        assertEquals(next, contexts.findById(next.id()).orElseThrow());
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
        } catch (LocationContextAcceptanceConflictException | LocationContextRetirementConflictException
                | LocationContextReplacementConflictException exception) {
            return exception;
        }
    }

    private LocationContext accept(LocationContext old) {
        return acceptance.acceptReviewRequired(old.id(), 7, 25, acceptedProbe());
    }

    private void assertConflictUnchanged(LocationContext old, long expectedRevision) {
        assertFailureUnchanged(LocationContextAcceptanceConflictException.class, old,
                expectedRevision, 25, ContinuityProbeResult.accepted(evidence(
                        pathCodec.decode(old.anchorLocationPath()), true, false)));
    }

    private <T extends Throwable> void assertFailureUnchanged(Class<T> failure, LocationContext old,
            long expectedRevision, long acceptedAtMs,
            ContinuityProbeResult<MacOsApfsLocationContextEvidence> result) {
        assertThrows(failure, () -> acceptance.acceptReviewRequired(old.id(), expectedRevision, acceptedAtMs, result));
        assertEquals(old, contexts.findById(old.id()).orElseThrow());
    }

    private void assertInvalidCurrent(LocationContext accepted, LocationContextAcceptanceEvidence envelope) {
        LocationContext altered = new LocationContext(accepted.id(), accepted.anchorLocationPath(),
                accepted.anchorLocationKey(), accepted.lifecycleStatus(), accepted.continuityStatus(),
                accepted.revision(), envelopeCodec.encode(envelope), accepted.createdAtMs(), accepted.updatedAtMs());
        assertThrows(IllegalStateException.class, () -> LocationContextAcceptanceAuthority.requireCurrentAccepted(altered));
    }

    private LocationContext active() {
        return contexts.insert(context(UUID.randomUUID().toString(), anchor(), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 7, null, 11, 15));
    }

    private LocationContext next() {
        return context(UUID.randomUUID().toString(), anchor(), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 19, null, 30, 30);
    }

    private LocationContext context(String id, LocationPath path, LifecycleStatus lifecycle,
            ContinuityStatus continuity, long revision, String evidence, long createdAtMs, long updatedAtMs) {
        return new LocationContext(id, pathCodec.encode(path), key(path), lifecycle, continuity,
                revision, evidence, createdAtMs, updatedAtMs);
    }

    private static ContinuityProbeResult<MacOsApfsLocationContextEvidence> acceptedProbe() {
        return ContinuityProbeResult.accepted(evidence(anchor(), true, false));
    }

    private static MacOsApfsLocationContextEvidence evidence(LocationPath path, boolean directory, boolean link) {
        return new MacOsApfsLocationContextEvidence(1, "macos-local-apfs", 1, path,
                LocationKeyCodec.encode(path), "apfs", "11111111-2222-3333-4444-555555555555",
                "2", directory, link, 99, MacOsApfsLocationContextEvidence.Diagnostics.empty());
    }

    private static LocationPath anchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive");
    }

    private static LocationPath otherAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Other");
    }

    private static String key(LocationPath path) {
        return LocationKeyCodec.encode(path).value();
    }

    @FunctionalInterface
    private interface Attempt {
        LocationContext run();
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
    }

    static class ReservationCoordinator {
        volatile ReservationGate gate;
        volatile boolean forceGuardMiss;
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
            ReservationGate gate = coordinator.gate;
            if (gate != null && Thread.currentThread() == gate.secondCaller.get()) {
                gate.secondAuthorityRead.countDown();
            }
            return super.findById(id);
        }

        @Override
        public int acceptReviewRequired(String id, long revision, long acceptedAtMs, String evidenceJson) {
            if (coordinator.forceGuardMiss) {
                return 0;
            }
            return super.acceptReviewRequired(id, revision, acceptedAtMs, evidenceJson);
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
