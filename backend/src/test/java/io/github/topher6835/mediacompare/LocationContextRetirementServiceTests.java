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
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextActivationService;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementService;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

@SpringBootTest
@Import(LocationContextRetirementServiceTests.ReservationHooks.class)
@DirtiesContext
class LocationContextRetirementServiceTests {
    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("retirement.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository catalog;
    @Autowired private LocationContextRepository contexts;
    @Autowired private LocationContextActivationService activation;
    @Autowired private LocationContextRetirementService retirement;
    @Autowired private ReservationCoordinator reservationCoordinator;

    private final LocationPathCodec pathCodec = new LocationPathCodec();

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void retiresOnceAndPreservesAllOtherFields() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE,
                ContinuityStatus.ACCEPTED, 7, "{\"profileVersion\":1}"));
        LocationContext expected = new LocationContext(active.id(), active.anchorLocationPath(),
                active.anchorLocationKey(), LifecycleStatus.RETIRED, active.continuityStatus(), 8,
                active.continuityEvidenceJson(), active.createdAtMs(), 25);

        assertEquals(expected, retirement.retireActive(active.id(), 7, 25));
        assertEquals(expected, contexts.findById(active.id()).orElseThrow());
        assertTrue(contexts.findActive().isEmpty());
    }

    @Test
    void acceptsEqualTransitionTimestamp() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        LocationContext retired = retirement.retireActive(active.id(), active.revision(), active.updatedAtMs());
        assertEquals(active.updatedAtMs(), retired.updatedAtMs());
        assertEquals(active.revision() + 1, retired.revision());
    }

    @Test
    void rejectsEarlierTransitionTimestampWithoutMutation() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> retirement.retireActive(active.id(), 7, 14));
        assertEquals(active, contexts.findById(active.id()).orElseThrow());
    }

    @Test
    void missingContextFailsWithoutMutation() {
        LocationContext unrelated = contexts.insert(context(unix("/Other"), LifecycleStatus.ACTIVE));
        assertThrows(NoSuchElementException.class,
                () -> retirement.retireActive(UUID.randomUUID().toString(), 7, 25));
        assertEquals(unrelated, contexts.findById(unrelated.id()).orElseThrow());
    }

    @Test
    void alreadyRetiredContextConflicts() {
        LocationContext retired = contexts.insert(context(unix("/Archive"), LifecycleStatus.RETIRED));
        assertThrows(LocationContextRetirementConflictException.class,
                () -> retirement.retireActive(retired.id(), 7, 25));
        assertEquals(retired, contexts.findById(retired.id()).orElseThrow());
    }

    @Test
    void staleExpectedRevisionConflicts() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        assertThrows(LocationContextRetirementConflictException.class,
                () -> retirement.retireActive(active.id(), 6, 25));
        assertEquals(active, contexts.findById(active.id()).orElseThrow());
    }

    @Test
    void rejectsRevisionOverflow() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, Long.MAX_VALUE, null));
        assertThrows(IllegalStateException.class,
                () -> retirement.retireActive(active.id(), Long.MAX_VALUE, 25));
        assertEquals(active, contexts.findById(active.id()).orElseThrow());
    }

    @Test
    void rejectsInvalidRequestArguments() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> retirement.retireActive("invalid", 7, 25));
        assertThrows(IllegalArgumentException.class, () -> retirement.retireActive(active.id(), -1, 25));
        assertThrows(IllegalArgumentException.class, () -> retirement.retireActive(active.id(), 7, -1));
        assertEquals(active, contexts.findById(active.id()).orElseThrow());
    }

    @Test
    void malformedTargetPathFailsClosed() {
        assertCorruptTarget("not-json", key(unix("/Archive")));
    }

    @Test
    void malformedTargetKeyFailsClosed() {
        assertCorruptTarget(path(unix("/Archive")), "lk1:zz");
    }

    @Test
    void targetPathKeyDisagreementFailsClosed() {
        assertCorruptTarget(path(unix("/Archive")), key(unix("/Other")));
    }

    @Test
    void boundSourcePreventsRetirementWithoutChangingEitherRow() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        Source source = catalog.insert(new Source(null, "Bound", "/Archive", "/Archive", 4,
                "UNIX", active.id(), "{\"bindingVersion\":1}", 10, 12));

        assertThrows(LocationContextRetirementConflictException.class,
                () -> retirement.retireActive(active.id(), 7, 25));
        assertEquals(active, contexts.findById(active.id()).orElseThrow());
        assertEquals(source, catalog.findSourceById(source.id()).orElseThrow());
    }

    @Test
    void unrelatedCorruptActiveContextIsUntouched() {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        LocationContext unrelated = contexts.insert(new LocationContext(UUID.randomUUID().toString(),
                "not-json", key(unix("/Other")), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 3, null, 11, 15));

        retirement.retireActive(active.id(), 7, 25);
        assertEquals(unrelated, contexts.findById(unrelated.id()).orElseThrow());
        assertEquals(LifecycleStatus.RETIRED, contexts.findById(active.id()).orElseThrow().lifecycleStatus());
    }

    @Test
    void concurrentRetirementsSerializeBeforeTargetRead() throws Exception {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        ReservationGate gate = new ReservationGate();
        reservationCoordinator.gate = gate;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> attemptRetirement(active.id()));
            try {
                assertTrue(gate.firstReserved.await(10, TimeUnit.SECONDS));
                var second = callers.submit(() -> attemptRetirement(active.id()));
                assertTrue(gate.secondEntered.await(10, TimeUnit.SECONDS));
                assertFalse(gate.secondReserved.await(500, TimeUnit.MILLISECONDS));
                assertEquals(1, gate.secondRead.getCount());

                gate.releaseFirst.countDown();
                Object firstResult = first.get(15, TimeUnit.SECONDS);
                Object secondResult = second.get(15, TimeUnit.SECONDS);
                assertTrue(firstResult instanceof LocationContext);
                assertTrue(secondResult instanceof LocationContextRetirementConflictException);
                assertEquals(0, gate.secondReserved.getCount());
                assertEquals(0, gate.secondRead.getCount());
                LocationContext retired = (LocationContext) firstResult;
                assertEquals(LifecycleStatus.RETIRED, retired.lifecycleStatus());
                assertEquals(active.revision() + 1, retired.revision());
                assertEquals(retired, contexts.findById(active.id()).orElseThrow());
            } finally {
                gate.releaseFirst.countDown();
                reservationCoordinator.gate = null;
            }
        }
    }

    @Test
    void overlappingCreationCanProceedAfterRetirementCommits() throws Exception {
        LocationContext active = contexts.insert(context(unix("/Archive"), LifecycleStatus.ACTIVE));
        LocationContext requested = context(unix("/Archive/Photos"), LifecycleStatus.ACTIVE);
        ReservationGate gate = new ReservationGate();
        reservationCoordinator.gate = gate;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> retirement.retireActive(active.id(), 7, 25));
            try {
                assertTrue(gate.firstReserved.await(10, TimeUnit.SECONDS));
                var second = callers.submit(() -> activation.createActive(requested));
                assertTrue(gate.secondEntered.await(10, TimeUnit.SECONDS));
                assertFalse(gate.secondReserved.await(500, TimeUnit.MILLISECONDS));
                gate.releaseFirst.countDown();
                assertEquals(LifecycleStatus.RETIRED, first.get(15, TimeUnit.SECONDS).lifecycleStatus());
                assertEquals(requested, second.get(15, TimeUnit.SECONDS));
                assertEquals(requested, contexts.findActive().getFirst());
                assertEquals(1, contexts.findActive().size());
            } finally {
                gate.releaseFirst.countDown();
                reservationCoordinator.gate = null;
            }
        }
    }

    private Object attemptRetirement(String contextId) {
        try {
            return retirement.retireActive(contextId, 7, 25);
        } catch (LocationContextRetirementConflictException conflict) {
            return conflict;
        }
    }

    private void assertCorruptTarget(String anchorPath, String anchorKey) {
        LocationContext active = contexts.insert(new LocationContext(UUID.randomUUID().toString(),
                anchorPath, anchorKey, LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED,
                7, null, 11, 15));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> retirement.retireActive(active.id(), 7, 25));
        assertTrue(failure.getMessage().contains(active.id()));
        assertEquals(active, contexts.findById(active.id()).orElseThrow());
    }

    private LocationContext context(LocationPath anchor, LifecycleStatus lifecycle) {
        return context(anchor, lifecycle, ContinuityStatus.REVIEW_REQUIRED, 7, null);
    }

    private LocationContext context(LocationPath anchor, LifecycleStatus lifecycle,
            ContinuityStatus continuity, long revision, String evidence) {
        return new LocationContext(UUID.randomUUID().toString(), path(anchor), key(anchor), lifecycle,
                continuity, revision, evidence, 11, 15);
    }

    private String path(LocationPath anchor) {
        return pathCodec.encode(anchor);
    }

    private static String key(LocationPath anchor) {
        return LocationKeyCodec.encode(anchor).value();
    }

    private static LocationPath unix(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
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
                gate.secondRead.countDown();
            }
            return super.findById(id);
        }
    }

    static class ReservationGate {
        final AtomicReference<Thread> firstCaller = new AtomicReference<>();
        final AtomicReference<Thread> secondCaller = new AtomicReference<>();
        final CountDownLatch firstReserved = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondReserved = new CountDownLatch(1);
        final CountDownLatch secondRead = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
    }
}
