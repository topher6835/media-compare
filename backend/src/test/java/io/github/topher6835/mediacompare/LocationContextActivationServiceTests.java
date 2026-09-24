package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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

import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextActivationService;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextStructuralOverlapException;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

@SpringBootTest
@Import(LocationContextActivationServiceTests.ReservationHooks.class)
@DirtiesContext
class LocationContextActivationServiceTests {
    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("contexts.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocationContextRepository contexts;
    @Autowired private LocationContextActivationService activation;
    @Autowired private ReservationCoordinator reservationCoordinator;

    private final LocationPathCodec pathCodec = new LocationPathCodec();

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void createsActiveContextAndPreservesCallerRevisionAndTimestamps() {
        LocationContext requested = context(unix("/Archive"), LifecycleStatus.ACTIVE);
        assertEquals(requested, activation.createActive(requested));
        assertEquals(requested, contexts.findById(requested.id()).orElseThrow());
        assertEquals(List.of(requested), contexts.findActive());
    }

    @Test
    void rejectsCallerSuppliedAcceptedContext() {
        LocationContext requested = new LocationContext(UUID.randomUUID().toString(),
                path(unix("/Archive")), key(unix("/Archive")), LifecycleStatus.ACTIVE,
                ContinuityStatus.ACCEPTED, 7, "{}", 11, 15);
        assertThrows(IllegalArgumentException.class, () -> activation.createActive(requested));
        assertTrue(contexts.findById(requested.id()).isEmpty());
    }

    @Test
    void rejectsReviewRequiredContextWithEvidence() {
        LocationContext requested = new LocationContext(UUID.randomUUID().toString(),
                path(unix("/Archive")), key(unix("/Archive")), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 7, "{}", 11, 15);
        assertThrows(IllegalArgumentException.class, () -> activation.createActive(requested));
        assertTrue(contexts.findById(requested.id()).isEmpty());
    }

    @Test
    void rejectsExactActiveAnchorWithoutChangingRows() {
        assertConflict(unix("/Archive"), unix("/Archive"));
    }

    @Test
    void rejectsRequestedDescendant() {
        assertConflict(unix("/Archive"), unix("/Archive/Photos"));
    }

    @Test
    void rejectsRequestedAncestor() {
        assertConflict(unix("/Archive/Photos"), unix("/Archive"));
    }

    @Test
    void allowsSiblings() {
        assertDistinct(unix("/Archive/Photos"), unix("/Archive/Videos"));
    }

    @Test
    void allowsDifferentWindowsDriveRoots() {
        assertDistinct(drive("C:\\Photos"), drive("D:\\Photos"));
    }

    @Test
    void rejectsAncestorWithinSameUncShare() {
        assertConflict(unc("\\\\server\\share"), unc("\\\\server\\share\\Photos"));
    }

    @Test
    void allowsDifferentUncShares() {
        assertDistinct(unc("\\\\server\\share-a"), unc("\\\\server\\share-b"));
    }

    @Test
    void allowsCaseDistinctAnchors() {
        assertDistinct(unix("/Archive/Photos"), unix("/Archive/photos"));
    }

    @Test
    void allowsUnicodeDistinctAnchors() {
        assertDistinct(unix("/Caf\u00e9"), unix("/Cafe\u0301"));
    }

    @Test
    void allowsDifferentDialects() {
        assertDistinct(unix("/Photos"), drive("C:\\Photos"));
    }

    @Test
    void ignoresOverlappingRetiredContext() {
        LocationContext retired = contexts.insert(context(unix("/Archive"), LifecycleStatus.RETIRED));
        LocationContext active = context(unix("/Archive/Photos"), LifecycleStatus.ACTIVE);
        assertEquals(active, activation.createActive(active));
        assertEquals(retired, contexts.findById(retired.id()).orElseThrow());
        assertEquals(List.of(active), contexts.findActive());
    }

    @Test
    void malformedPersistedActivePathFailsClosed() {
        assertCorruptActiveAnchor("not-json", key(unix("/Archive")));
    }

    @Test
    void malformedPersistedActiveKeyFailsClosed() {
        assertCorruptActiveAnchor(path(unix("/Archive")), "lk1:zz");
    }

    @Test
    void persistedActivePathKeyDisagreementFailsClosed() {
        assertCorruptActiveAnchor(path(unix("/Archive")), key(unix("/Other")));
    }

    @Test
    void rejectsRequestedRetiredContext() {
        assertThrows(IllegalArgumentException.class,
                () -> activation.createActive(context(unix("/Archive"), LifecycleStatus.RETIRED)));
        assertTrue(contexts.findActive().isEmpty());
    }

    @Test
    void rejectsInvalidRequestedAnchor() {
        LocationContext requested = new LocationContext(UUID.randomUUID().toString(),
                path(unix("/Archive")), key(unix("/Other")), LifecycleStatus.ACTIVE,
                ContinuityStatus.REVIEW_REQUIRED, 7, null, 11, 15);
        assertThrows(IllegalArgumentException.class, () -> activation.createActive(requested));
        assertTrue(contexts.findActive().isEmpty());
    }

    @Test
    void concurrentOverlappingCreationsSerializeBeforeActiveRead() throws Exception {
        LocationContext ancestor = context(unix("/Archive"), LifecycleStatus.ACTIVE);
        LocationContext descendant = context(unix("/Archive/Photos"), LifecycleStatus.ACTIVE);
        ReservationGate gate = new ReservationGate();
        reservationCoordinator.gate = gate;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> attempt(ancestor));
            try {
                assertTrue(gate.firstReserved.await(10, TimeUnit.SECONDS));
                var second = callers.submit(() -> attempt(descendant));
                assertTrue(gate.secondEntered.await(10, TimeUnit.SECONDS));

                // The first transaction still holds SQLite's writer. The second has entered
                // reserveWrite(), but must not pass the real reservation or read ACTIVE rows.
                assertFalse(gate.secondReserved.await(500, TimeUnit.MILLISECONDS));
                assertEquals(1, gate.secondRead.getCount());

                gate.releaseFirst.countDown();
                Object firstResult = first.get(15, TimeUnit.SECONDS);
                Object secondResult = second.get(15, TimeUnit.SECONDS);
                assertEquals(0, gate.secondReserved.getCount());
                assertEquals(0, gate.secondRead.getCount());
                assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(LocationContext.class::isInstance).count());
                assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(LocationContextStructuralOverlapException.class::isInstance).count());
                LocationContext winner = firstResult instanceof LocationContext context ? context : (LocationContext) secondResult;
                LocationContextStructuralOverlapException conflict = firstResult instanceof LocationContextStructuralOverlapException overlap
                    ? overlap : (LocationContextStructuralOverlapException) secondResult;
                assertEquals(winner.id(), conflict.existingContextId());
                assertEquals(List.of(winner), contexts.findActive());
            } finally {
                gate.releaseFirst.countDown();
                reservationCoordinator.gate = null;
            }
        }
    }

    private Object attempt(LocationContext requested) {
        try {
            return activation.createActive(requested);
        } catch (LocationContextStructuralOverlapException conflict) {
            return conflict;
        }
    }

    private void assertConflict(LocationPath existingAnchor, LocationPath requestedAnchor) {
        LocationContext existing = activation.createActive(context(existingAnchor, LifecycleStatus.ACTIVE));
        LocationContext requested = context(requestedAnchor, LifecycleStatus.ACTIVE);
        var conflict = assertThrows(LocationContextStructuralOverlapException.class,
                () -> activation.createActive(requested));
        assertEquals(existing.id(), conflict.existingContextId());
        assertEquals(List.of(existing), contexts.findActive());
        assertTrue(contexts.findById(requested.id()).isEmpty());
    }

    private void assertDistinct(LocationPath firstAnchor, LocationPath secondAnchor) {
        LocationContext first = activation.createActive(context(firstAnchor, LifecycleStatus.ACTIVE));
        LocationContext second = activation.createActive(context(secondAnchor, LifecycleStatus.ACTIVE));
        assertEquals(2, contexts.findActive().size());
        assertEquals(first, contexts.findById(first.id()).orElseThrow());
        assertEquals(second, contexts.findById(second.id()).orElseThrow());
    }

    private void assertCorruptActiveAnchor(String path, String key) {
        LocationContext corrupt = contexts.insert(new LocationContext(UUID.randomUUID().toString(),
                path, key, LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 0, null, 1, 2));
        LocationContext requested = context(unix("/New"), LifecycleStatus.ACTIVE);
        var failure = assertThrows(IllegalStateException.class, () -> activation.createActive(requested));
        assertTrue(failure.getMessage().contains(corrupt.id()));
        assertEquals(List.of(corrupt), contexts.findActive());
        assertTrue(contexts.findById(requested.id()).isEmpty());
    }

    private LocationContext context(LocationPath anchor, LifecycleStatus lifecycle) {
        return new LocationContext(UUID.randomUUID().toString(), path(anchor), key(anchor), lifecycle,
                ContinuityStatus.REVIEW_REQUIRED, 7, null, 11, 15);
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

    private static LocationPath drive(String value) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, value);
    }

    private static LocationPath unc(String value) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_UNC, value);
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
            ReservationGate current = coordinator.gate;
            if (current == null) {
                super.reserveWrite();
                return;
            }
            if (current.firstCaller.compareAndSet(null, Thread.currentThread())) {
                super.reserveWrite();
                current.firstReserved.countDown();
                try {
                    if (!current.releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("First writer was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding first writer", exception);
                }
            } else {
                current.secondCaller.set(Thread.currentThread());
                current.secondEntered.countDown();
                super.reserveWrite();
                current.secondReserved.countDown();
            }
        }

        @Override
        public List<LocationContext> findActive() {
            ReservationGate current = coordinator.gate;
            if (current != null && Thread.currentThread() == current.secondCaller.get()) {
                current.secondRead.countDown();
            }
            return super.findActive();
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
