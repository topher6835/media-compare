package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextActivationService;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementService;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementService;
import io.github.topher6835.mediacompare.catalog.LocationContextStructuralOverlapException;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

@SpringBootTest
@Import(LocationContextReplacementServiceTests.ReservationHooks.class)
@DirtiesContext
class LocationContextReplacementServiceTests {
    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("replacement.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository catalog;
    @Autowired private LocationContextRepository contexts;
    @Autowired private LocationContextActivationService activation;
    @Autowired private LocationContextRetirementService retirement;
    @Autowired private LocationContextReplacementService replacement;
    @Autowired private ReservationCoordinator coordinator;

    private final LocationPathCodec pathCodec = new LocationPathCodec();

    @BeforeEach
    @AfterEach
    void clear() {
        coordinator.gate = null;
        coordinator.retirementCalls.set(0);
        coordinator.forceGuardMiss = false;
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void replacesOnePeriodAndPreservesBothRowsFields() {
        LocationContext old = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE,
                ContinuityStatus.ACCEPTED, 7, "{\"old\":true}", 11, 15));
        LocationContext next = context(unix("/Archive"), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 19, null, 25, 30);

        assertEquals(next, replacement.replaceActive(old.id(), 7, 25, next));
        assertEquals(new LocationContext(old.id(), old.anchorLocationPath(), old.anchorLocationKey(),
                LifecycleStatus.RETIRED, old.continuityStatus(), 8, old.continuityEvidenceJson(), 11, 25),
                contexts.findById(old.id()).orElseThrow());
        assertEquals(next, contexts.findById(next.id()).orElseThrow());
        assertEquals(List.of(next), contexts.findActive());
        assertNull(next.continuityEvidenceJson());
        assertEquals(1, coordinator.retirementCalls.get());
    }

    @Test
    void acceptsEquivalentValidPathJsonSpelling() {
        LocationContext old = active("/Archive");
        LocationContext next = newContext(UUID.randomUUID().toString(),
                " [ \"lp1\", \"unix\", [], [\"Archive\"] ] ", old.anchorLocationKey(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 25);

        assertEquals(next, replacement.replaceActive(old.id(), 7, 25, next));
        assertEquals(List.of(next), contexts.findActive());
    }

    @Test
    void rejectsSameId() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(old.id(), old.anchorLocationPath(), old.anchorLocationKey(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 25));
    }

    @Test
    void rejectsRetiredNewRow() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), old.anchorLocationPath(),
                old.anchorLocationKey(), LifecycleStatus.RETIRED, ContinuityStatus.REVIEW_REQUIRED, null, 25));
    }

    @Test
    void rejectsAcceptedNewRowEvenWithEvidence() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), old.anchorLocationPath(),
                old.anchorLocationKey(), LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED,
                "{\"profileVersion\":1}", 25));
    }

    @Test
    void rejectsReviewRequiredNewRowWithEvidence() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), old.anchorLocationPath(),
                old.anchorLocationKey(), LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, "{}", 25));
    }

    @Test
    void rejectsDifferentStructuredAnchor() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, next(unix("/Archive/Photos")));
    }

    @Test
    void rejectsMalformedNewPath() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), "not-json", old.anchorLocationKey(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 25));
    }

    @Test
    void rejectsMalformedNewKey() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), old.anchorLocationPath(), "lk1:zz",
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 25));
    }

    @Test
    void rejectsNewPathKeyDisagreement() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), old.anchorLocationPath(),
                key(unix("/Other")), LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 25));
    }

    @Test
    void rejectsNewCreationTimestampOutsideTransition() {
        LocationContext old = active("/Archive");
        assertInvalidNew(old, newContext(UUID.randomUUID().toString(), old.anchorLocationPath(),
                old.anchorLocationKey(), LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 26));
    }

    @Test
    void rejectsInvalidBasicArguments() {
        LocationContext old = active("/Archive");
        LocationContext next = next(unix("/Archive"));
        assertThrows(IllegalArgumentException.class, () -> replacement.replaceActive("invalid", 7, 25, next));
        assertThrows(IllegalArgumentException.class, () -> replacement.replaceActive(old.id(), -1, 25, next));
        assertThrows(IllegalArgumentException.class, () -> replacement.replaceActive(old.id(), 7, -1, next));
        assertThrows(IllegalArgumentException.class, () -> replacement.replaceActive(old.id(), 7, 25, null));
        assertEquals(old, contexts.findById(old.id()).orElseThrow());
    }

    @Test
    void missingOldContextFailsWithoutInsertingNew() {
        LocationContext next = next(unix("/Archive"));
        assertThrows(NoSuchElementException.class,
                () -> replacement.replaceActive(UUID.randomUUID().toString(), 7, 25, next));
        assertTrue(contexts.findById(next.id()).isEmpty());
    }

    @Test
    void alreadyRetiredOldContextConflicts() {
        LocationContext old = contexts.insert(context(unix("/Archive"), LifecycleStatus.RETIRED));
        assertConflictWithoutMutation(old, next(unix("/Archive")), 7);
    }

    @Test
    void staleExpectedRevisionConflicts() {
        LocationContext old = active("/Archive");
        assertConflictWithoutMutation(old, next(unix("/Archive")), 6);
    }

    @Test
    void revisionOverflowFailsWithoutMutation() {
        LocationContext old = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, Long.MAX_VALUE, null, 11, 15));
        LocationContext next = next(unix("/Archive"));
        assertThrows(IllegalStateException.class,
                () -> replacement.replaceActive(old.id(), Long.MAX_VALUE, 25, next));
        assertUnchanged(old, next);
    }

    @Test
    void timestampRegressionFailsWithoutMutation() {
        LocationContext old = active("/Archive");
        LocationContext next = newContext(UUID.randomUUID().toString(), old.anchorLocationPath(),
                old.anchorLocationKey(), LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 14);
        assertThrows(IllegalArgumentException.class, () -> replacement.replaceActive(old.id(), 7, 14, next));
        assertUnchanged(old, next);
    }

    @Test
    void corruptOldAnchorFailsClosed() {
        LocationContext old = contexts.insert(newContext(UUID.randomUUID().toString(), "not-json",
                key(unix("/Archive")), LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 11));
        LocationContext next = next(unix("/Archive"));
        assertThrows(IllegalStateException.class, () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertUnchanged(old, next);
    }

    @Test
    void boundOldContextAndSourceRemainUnchanged() {
        LocationContext old = active("/Archive");
        Source source = catalog.insert(new Source(null, "Bound", "/Archive", "/Archive", 4,
                "UNIX", old.id(), "{\"bindingVersion\":1}", 10, 12));
        LocationContext next = next(unix("/Archive"));

        assertConflictWithoutMutation(old, next, 7);
        assertEquals(source, catalog.findSourceById(source.id()).orElseThrow());
    }

    @Test
    void unrelatedOverlappingActiveContextRejectsReplacement() {
        LocationContext old = active("/Archive");
        LocationContext overlap = contexts.insert(context(unix("/Archive/Photos"), LifecycleStatus.ACTIVE));
        LocationContext next = next(unix("/Archive"));

        var failure = assertThrows(LocationContextStructuralOverlapException.class,
                () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertEquals(overlap.id(), failure.existingContextId());
        assertUnchanged(old, next);
        assertEquals(overlap, contexts.findById(overlap.id()).orElseThrow());
    }

    @Test
    void malformedUnrelatedActiveAnchorFailsClosed() {
        LocationContext old = active("/Archive");
        LocationContext corrupt = contexts.insert(newContext(UUID.randomUUID().toString(), "not-json",
                key(unix("/Other")), LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 11));
        LocationContext next = next(unix("/Archive"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertTrue(failure.getMessage().contains(corrupt.id()));
        assertUnchanged(old, next);
        assertEquals(corrupt, contexts.findById(corrupt.id()).orElseThrow());
    }

    @Test
    void unrelatedNonoverlappingActiveContextIsPreserved() {
        LocationContext old = active("/Archive");
        LocationContext unrelated = contexts.insert(context(unix("/Other"), LifecycleStatus.ACTIVE));
        LocationContext next = next(unix("/Archive"));

        assertEquals(next, replacement.replaceActive(old.id(), 7, 25, next));
        assertEquals(unrelated, contexts.findById(unrelated.id()).orElseThrow());
        assertEquals(2, contexts.findActive().size());
    }

    @Test
    void historicalIdCollisionRollsBackExecutedRetirement() {
        LocationContext old = active("/Archive");
        LocationContext historical = contexts.insert(context(unix("/Other"), LifecycleStatus.RETIRED));
        LocationContext next = newContext(historical.id(), old.anchorLocationPath(), old.anchorLocationKey(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, null, 25);

        assertThrows(DataAccessException.class, () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertEquals(1, coordinator.retirementCalls.get());
        assertEquals(old, contexts.findById(old.id()).orElseThrow());
        assertEquals(historical, contexts.findById(historical.id()).orElseThrow());
        assertEquals(List.of(old), contexts.findActive());
    }

    @Test
    void guardedRetirementMissBecomesReplacementConflict() {
        LocationContext old = active("/Archive");
        LocationContext next = next(unix("/Archive"));
        coordinator.forceGuardMiss = true;

        assertConflictWithoutMutation(old, next, 7);
        assertEquals(1, coordinator.retirementCalls.get());
    }

    @Test
    void concurrentReplacementsSerializeBeforeTargetRead() throws Exception {
        LocationContext old = active("/Archive");
        LocationContext firstNext = next(unix("/Archive"));
        LocationContext secondNext = next(unix("/Archive"));

        RaceResults results = race(() -> replacement.replaceActive(old.id(), 7, 25, firstNext),
                () -> replacement.replaceActive(old.id(), 7, 25, secondNext));
        assertEquals(firstNext, results.first());
        assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        assertEquals(List.of(firstNext), contexts.findActive());
        assertEquals(8, contexts.findById(old.id()).orElseThrow().revision());
        assertTrue(contexts.findById(secondNext.id()).isEmpty());
    }

    @Test
    void replacementAndRetirementSerializeWithOneWinner() throws Exception {
        LocationContext old = active("/Archive");
        LocationContext next = next(unix("/Archive"));

        RaceResults results = race(() -> replacement.replaceActive(old.id(), 7, 25, next),
                () -> retirement.retireActive(old.id(), 7, 25));
        assertEquals(next, results.first());
        assertTrue(results.second() instanceof LocationContextRetirementConflictException);
        assertEquals(List.of(next), contexts.findActive());
        assertEquals(8, contexts.findById(old.id()).orElseThrow().revision());
    }

    @Test
    void retirementFirstPreventsReplacementWithoutPartialInsert() throws Exception {
        LocationContext old = active("/Archive");
        LocationContext next = next(unix("/Archive"));

        RaceResults results = race(() -> retirement.retireActive(old.id(), 7, 25),
                () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        assertTrue(contexts.findActive().isEmpty());
        assertTrue(contexts.findById(next.id()).isEmpty());
        assertEquals(8, contexts.findById(old.id()).orElseThrow().revision());
    }

    @Test
    void overlappingCreationWaitsForReplacementAndConflicts() throws Exception {
        LocationContext old = active("/Archive");
        LocationContext next = next(unix("/Archive"));
        LocationContext creation = context(unix("/Archive/Photos"), LifecycleStatus.ACTIVE);

        RaceResults results = race(() -> replacement.replaceActive(old.id(), 7, 25, next),
                () -> activation.createActive(creation));
        assertEquals(next, results.first());
        assertTrue(results.second() instanceof LocationContextStructuralOverlapException);
        assertEquals(List.of(next), contexts.findActive());
        assertTrue(contexts.findById(creation.id()).isEmpty());
    }

    @Test
    void replacementWaitsForOverlappingCreationAndThenSucceeds() throws Exception {
        LocationContext old = active("/Archive");
        LocationContext next = next(unix("/Archive"));
        LocationContext creation = context(unix("/Archive/Photos"), LifecycleStatus.ACTIVE);

        RaceResults results = race(() -> activation.createActive(creation),
                () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertTrue(results.first() instanceof LocationContextStructuralOverlapException);
        assertEquals(next, results.second());
        assertEquals(List.of(next), contexts.findActive());
        assertTrue(contexts.findById(creation.id()).isEmpty());
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
        } catch (LocationContextReplacementConflictException | LocationContextRetirementConflictException
                | LocationContextStructuralOverlapException exception) {
            return exception;
        }
    }

    private void assertInvalidNew(LocationContext old, LocationContext next) {
        assertThrows(IllegalArgumentException.class,
                () -> replacement.replaceActive(old.id(), 7, 25, next));
        assertUnchanged(old, next);
    }

    private void assertConflictWithoutMutation(LocationContext old, LocationContext next, long expectedRevision) {
        assertThrows(LocationContextReplacementConflictException.class,
                () -> replacement.replaceActive(old.id(), expectedRevision, 25, next));
        assertUnchanged(old, next);
    }

    private void assertUnchanged(LocationContext old, LocationContext next) {
        assertEquals(old, contexts.findById(old.id()).orElseThrow());
        if (!next.id().equals(old.id())) {
            assertTrue(contexts.findById(next.id()).isEmpty());
        }
    }

    private LocationContext active(String anchor) {
        return contexts.insert(context(unix(anchor), LifecycleStatus.ACTIVE));
    }

    private LocationContext next(LocationPath anchor) {
        return context(anchor, LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 19, null, 25, 30);
    }

    private LocationContext context(LocationPath anchor, LifecycleStatus lifecycle) {
        return context(anchor, lifecycle, ContinuityStatus.REVIEW_REQUIRED, 7, null, 11, 15);
    }

    private LocationContext context(LocationPath anchor, LifecycleStatus lifecycle, ContinuityStatus continuity,
            long revision, String evidence, long createdAtMs, long updatedAtMs) {
        return new LocationContext(UUID.randomUUID().toString(), pathCodec.encode(anchor), key(anchor), lifecycle,
                continuity, revision, evidence, createdAtMs, updatedAtMs);
    }

    private LocationContext newContext(String id, String path, String key, LifecycleStatus lifecycle,
            ContinuityStatus continuity, String evidence, long createdAtMs) {
        return new LocationContext(id, path, key, lifecycle, continuity, 7, evidence,
                createdAtMs, Math.max(createdAtMs, 30));
    }

    private static String key(LocationPath anchor) {
        return LocationKeyCodec.encode(anchor).value();
    }

    private static LocationPath unix(String path) {
        return LocationPathParser.parse(LocationDialect.UNIX, path);
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
        final AtomicInteger retirementCalls = new AtomicInteger();
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
        public java.util.Optional<LocationContext> findById(String id) {
            markAuthorityRead();
            return super.findById(id);
        }

        @Override
        public List<LocationContext> findActive() {
            markAuthorityRead();
            return super.findActive();
        }

        @Override
        public int retireActive(String contextId, long expectedRevision, long retiredAtMs) {
            coordinator.retirementCalls.incrementAndGet();
            if (coordinator.forceGuardMiss) {
                return 0;
            }
            return super.retireActive(contextId, expectedRevision, retiredAtMs);
        }

        private void markAuthorityRead() {
            ReservationGate gate = coordinator.gate;
            if (gate != null && Thread.currentThread() == gate.secondCaller.get()) {
                gate.secondAuthorityRead.countDown();
            }
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
