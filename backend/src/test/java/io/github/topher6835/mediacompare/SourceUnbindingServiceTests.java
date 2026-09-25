package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementService;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementService;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriod;
import io.github.topher6835.mediacompare.catalog.SourceBindingPeriodRepository;
import io.github.topher6835.mediacompare.catalog.SourceMembership;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import io.github.topher6835.mediacompare.catalog.SourceUnbindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourceUnbindingService;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.SourceMembershipPublicationService;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import io.github.topher6835.mediacompare.scan.Version3ScanExecutionService;
import io.github.topher6835.mediacompare.scan.authority.ChildStorageBoundary;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;

@SpringBootTest
@Import(SourceUnbindingServiceTests.FailureAndReservationHooks.class)
@DirtiesContext
class SourceUnbindingServiceTests {
    private static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String VOLUME_ID = "11111111-2222-3333-4444-555555555555";
    private static final LocationPath ANCHOR = path("/Volumes/Archive");
    private static final LocationPath ROOT = path("/Volumes/Archive/Photos");
    private static final LocationPath FILE = path("/Volumes/Archive/Photos/a.jpg");

    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("unbinding.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository sources;
    @Autowired private LocationContextRepository contexts;
    @Autowired private SourceBindingPeriodRepository periods;
    @Autowired private SourceMembershipRepository memberships;
    @Autowired private SourceUnbindingService unbinding;
    @Autowired private SourceMembershipPublicationService publisher;
    @Autowired private ScanRepository scans;
    @Autowired private Version3ScanExecutionService admission;
    @Autowired private LocationContextRetirementService retirement;
    @Autowired private LocationContextReplacementService replacement;
    @Autowired private Hooks hooks;

    @BeforeEach
    @AfterEach
    void clear() {
        hooks.failure = null;
        hooks.gate = null;
        jdbc.update("DELETE FROM content_hash");
        jdbc.update("DELETE FROM analysis_record");
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM scan_run_source");
        jdbc.update("DELETE FROM scan_run");
        jdbc.update("DELETE FROM content_record");
        jdbc.update("DELETE FROM source_binding_period");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void unbindPreservesStructuredRootAndAllMembershipAndArtifactHistory() {
        Source bound = seed(5);
        insertRunSource(11, "DISCOVERING");
        insertMembership(40, "PRESENT", 7);
        insertMembership(41, "MISSING", 9);
        List<String> files = rows("SELECT * FROM file_entry ORDER BY id");
        List<String> content = rows("SELECT * FROM content_record ORDER BY id");
        List<String> analyses = rows("SELECT * FROM analysis_record ORDER BY id");
        List<String> hashes = rows("SELECT * FROM content_hash ORDER BY analysis_record_id");
        List<String> membersBefore = rows("""
                SELECT id, source_id, file_entry_id, relative_path, path_key, presence_status,
                       observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                       last_positive_scan_run_source_id, last_positive_traversal_generation,
                       observed_source_location_revision, observed_location_context_revision
                FROM source_membership ORDER BY id
                """);

        Source unbound = unbinding.unbind(1, 5, 30);

        assertEquals(new Source(1L, bound.name(), bound.rootPath(), bound.rootPathKey(), 6,
                bound.rootPathDialect(), null, null, bound.createdAtMs(), 30), unbound);
        assertEquals(unbound, sources.findSourceById(1).orElseThrow());
        SourceBindingPeriod period = periods.findBySourceId(1).getFirst();
        assertTrue(periods.findOpenBySourceId(1).isEmpty());
        assertEquals(5, period.boundSourceLocationRevision());
        assertEquals(6L, period.unboundSourceLocationRevision());
        assertEquals(30L, period.unboundAtMs());
        assertEquals(bound.bindingEvidenceJson(), period.bindingEvidenceJson());
        assertEquals(List.of("40|RETIRED|PRESENT|8", "41|RETIRED|MISSING|10"), rows("""
                SELECT id, applicability_status, presence_status, membership_revision
                FROM source_membership ORDER BY id
                """));
        assertEquals(membersBefore, rows("""
                SELECT id, source_id, file_entry_id, relative_path, path_key, presence_status,
                       observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                       last_positive_scan_run_source_id, last_positive_traversal_generation,
                       observed_source_location_revision, observed_location_context_revision
                FROM source_membership ORDER BY id
                """));
        assertEquals(files, rows("SELECT * FROM file_entry ORDER BY id"));
        assertEquals(content, rows("SELECT * FROM content_record ORDER BY id"));
        assertEquals(analyses, rows("SELECT * FROM analysis_record ORDER BY id"));
        assertEquals(hashes, rows("SELECT * FROM content_hash ORDER BY analysis_record_id"));
    }

    @Test
    void zeroMembershipsCanUnbindEvenWhenContextNoLongerAccepted() {
        Source bound = seed(5);
        jdbc.update("UPDATE location_context SET continuity_status = 'REVIEW_REQUIRED' WHERE id = ?", CONTEXT_ID);
        assertEquals(6, unbinding.unbind(1, 5, 25).locationRevision());
        assertEquals(bound.rootPathKey(), sources.findSourceById(1).orElseThrow().rootPathKey());
        assertEquals(0, count("SELECT COUNT(*) FROM source_membership"));
    }

    @Test
    void neverBoundAlreadyUnboundStaleTimestampAndMissingSourceFailWithoutMutation() {
        Source bound = seed(5);
        jdbc.update("""
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    created_at_ms, updated_at_ms)
                VALUES (2, 'Never', '/legacy', '/legacy', 0, 1, 2)
                """);
        assertThrows(SourceUnbindingConflictException.class, () -> unbinding.unbind(2, 0, 30));
        assertThrows(NoSuchElementException.class, () -> unbinding.unbind(99, 0, 30));
        assertThrows(SourceUnbindingConflictException.class, () -> unbinding.unbind(1, 4, 30));
        assertThrows(SourceUnbindingConflictException.class, () -> unbinding.unbind(1, 5, 24));
        assertEquals(bound, sources.findSourceById(1).orElseThrow());
        assertTrue(periods.findOpenBySourceId(1).isPresent());
        unbinding.unbind(1, 5, 30);
        assertThrows(SourceUnbindingConflictException.class, () -> unbinding.unbind(1, 6, 31));
        assertEquals(6, sources.findSourceById(1).orElseThrow().locationRevision());
    }

    @Test
    void revisionOverflowIsRejectedWithoutMutation() {
        Source bound = seed(Long.MAX_VALUE);
        assertThrows(SourceUnbindingConflictException.class,
                () -> unbinding.unbind(1, Long.MAX_VALUE, 30));
        assertEquals(bound, sources.findSourceById(1).orElseThrow());
        assertTrue(periods.findOpenBySourceId(1).isPresent());
    }

    @Test
    void missingOrMismatchedPeriodIsAnIntegrityFailure() {
        Source bound = seed(5);
        jdbc.update("DELETE FROM source_binding_period WHERE source_id = 1");
        assertThrows(IllegalStateException.class, () -> unbinding.unbind(1, 5, 30));
        assertEquals(bound, sources.findSourceById(1).orElseThrow());
        jdbc.update("""
                INSERT INTO source_binding_period (source_id, bound_source_location_revision,
                    location_context_id, root_path_dialect, root_path, root_path_key,
                    binding_evidence_json, bound_at_ms)
                VALUES (1, 5, ?, 'unix', ?, 'wrong-key', ?, 25)
                """, CONTEXT_ID, bound.rootPath(), bound.bindingEvidenceJson());
        assertThrows(IllegalStateException.class, () -> unbinding.unbind(1, 5, 30));
        assertEquals(bound, sources.findSourceById(1).orElseThrow());
        assertTrue(periods.findOpenBySourceId(1).isPresent());
    }

    @Test
    void malformedCurrentBindingEvidenceCanBeWithdrawnWhenPeriodMatchesExactly() {
        Source bound = seed(5);
        String malformedEvidence = "{malformed binding evidence";
        jdbc.update("UPDATE source SET binding_evidence_json = ? WHERE id = 1", malformedEvidence);
        jdbc.update("UPDATE source_binding_period SET binding_evidence_json = ? WHERE source_id = 1",
                malformedEvidence);

        Source unbound = unbinding.unbind(1, 5, 30);

        assertEquals(6, unbound.locationRevision());
        assertEquals(bound.rootPath(), unbound.rootPath());
        assertEquals(bound.rootPathKey(), unbound.rootPathKey());
        assertEquals(bound.rootPathDialect(), unbound.rootPathDialect());
        assertNull(unbound.boundLocationContextId());
        assertNull(unbound.bindingEvidenceJson());
        SourceBindingPeriod closed = periods.findBySourceId(1).getFirst();
        assertEquals(malformedEvidence, closed.bindingEvidenceJson());
        assertEquals(6L, closed.unboundSourceLocationRevision());
        assertEquals(30L, closed.unboundAtMs());
        assertTrue(periods.findOpenBySourceId(1).isEmpty());
    }

    @Test
    void failuresAtEachWriteStepRollBackEverything() {
        for (String failure : List.of("period", "memberships", "source")) {
            Source bound = seed(5);
            insertMembership(40, "PRESENT", 7);
            SourceBindingPeriod before = periods.findOpenBySourceId(1).orElseThrow();
            SourceMembership membership = memberships.findMembershipById(40).orElseThrow();
            hooks.failure = failure;
            assertThrows(RuntimeException.class, () -> unbinding.unbind(1, 5, 30));
            hooks.failure = null;
            assertEquals(bound, sources.findSourceById(1).orElseThrow());
            assertEquals(before, periods.findOpenBySourceId(1).orElseThrow());
            assertEquals(membership, memberships.findMembershipById(40).orElseThrow());
            clear();
        }
    }

    @Test
    void concurrentUnbindHasOneWinnerAndOneRetirement() throws Exception {
        seed(5);
        insertMembership(40, "PRESENT", 7);
        RaceResults results = race(() -> unbinding.unbind(1, 5, 30),
                () -> unbinding.unbind(1, 5, 30));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof SourceUnbindingConflictException);
        assertEquals(6, sources.findSourceById(1).orElseThrow().locationRevision());
        assertEquals(6L, periods.findBySourceId(1).getFirst().unboundSourceLocationRevision());
        assertEquals(8, memberships.findMembershipById(40).orElseThrow().membershipRevision());
    }

    @Test
    void publicationAndUnbindSerializeInEitherOrder() throws Exception {
        seed(5);
        insertRunSource(11, "DISCOVERING");
        RaceResults publicationFirst = race(() -> publisher.publish(candidate(), 11, 1, 28),
                () -> unbinding.unbind(1, 5, 30));
        assertTrue(publicationFirst.first() instanceof SourceMembership);
        assertTrue(publicationFirst.second() instanceof Source);
        assertEquals("RETIRED", memberships.findMembershipById(
                ((SourceMembership) publicationFirst.first()).id()).orElseThrow().applicabilityStatus());
        clear();

        seed(5);
        insertRunSource(11, "DISCOVERING");
        RaceResults unbindFirst = race(() -> unbinding.unbind(1, 5, 30),
                () -> publisher.publish(candidate(), 11, 1, 31));
        assertTrue(unbindFirst.first() instanceof Source);
        assertTrue(unbindFirst.second() instanceof IllegalStateException);
        assertEquals(0, count("SELECT COUNT(*) FROM source_membership WHERE applicability_status = 'ACTIVE'"));
        assertEquals(0, count("SELECT COUNT(*) FROM file_entry"));
    }

    @Test
    void reconciliationAndUnbindSerializeWithoutNewMissingClaimAfterUnbind() throws Exception {
        seed(5);
        insertRunSource(11, "DISCOVERED");
        insertMembership(40, "PRESENT", 7);
        jdbc.update("UPDATE source_membership SET last_positive_scan_run_source_id = NULL WHERE id = 40");
        RaceResults reconciliationFirst = race(() -> publisher.reconcile(claim(),
                scans.findScanRunSourceById(11).orElseThrow(), 28),
                () -> unbinding.unbind(1, 5, 30));
        assertEquals(1, reconciliationFirst.first());
        assertTrue(reconciliationFirst.second() instanceof Source);
        assertEquals("RETIRED", memberships.findMembershipById(40).orElseThrow().applicabilityStatus());
        assertEquals("MISSING", memberships.findMembershipById(40).orElseThrow().presenceStatus());
        clear();

        seed(5);
        insertRunSource(11, "DISCOVERED");
        insertMembership(40, "PRESENT", 7);
        jdbc.update("UPDATE source_membership SET last_positive_scan_run_source_id = NULL WHERE id = 40");
        RaceResults unbindFirst = race(() -> unbinding.unbind(1, 5, 30),
                () -> publisher.reconcile(claim(), scans.findScanRunSourceById(11).orElseThrow(), 31));
        assertTrue(unbindFirst.first() instanceof Source);
        assertTrue(unbindFirst.second() instanceof IllegalStateException);
        assertEquals("RETIRED", memberships.findMembershipById(40).orElseThrow().applicabilityStatus());
        assertEquals("PRESENT", memberships.findMembershipById(40).orElseThrow().presenceStatus());
    }

    @Test
    void formerContextCanRetireOrBeReplacedAfterUnbind() {
        seed(5);
        unbinding.unbind(1, 5, 30);
        assertEquals(LocationContext.LifecycleStatus.RETIRED,
                retirement.retireActive(CONTEXT_ID, 3, 31).lifecycleStatus());
        assertEquals(1, periods.findBySourceId(1).size());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM location_context WHERE id = ?", CONTEXT_ID));
        assertEquals(0, count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
        clear();

        seed(5);
        unbinding.unbind(1, 5, 30);
        LocationContext next = new LocationContext(UUID.randomUUID().toString(),
                new LocationPathCodec().encode(ANCHOR), LocationKeyCodec.encode(ANCHOR).value(),
                LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, 31, 31);
        assertEquals(next, replacement.replaceActive(CONTEXT_ID, 3, 31, next));
        assertEquals(1, periods.findBySourceId(1).size());
        assertEquals(0, count("SELECT COUNT(*) FROM pragma_foreign_key_check"));
    }

    @Test
    void v3AdmissionCannotUseClosedHistoryOrOldScanSnapshot() {
        seed(5);
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (80, 'INDEX', 'PENDING', 1, '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation)
                VALUES (81, 80, 1, 'PENDING', 5, 0)
                """);
        unbinding.unbind(1, 5, 30);
        assertThrows(Version2ExecutionConflictException.class, () -> admission.create(80));
        jdbc.update("UPDATE scan_run_source SET source_location_revision = 6 WHERE id = 81");
        assertThrows(Version2ExecutionConflictException.class, () -> admission.create(80));
        assertEquals(1, periods.findBySourceId(1).size());
    }

    private Source seed(long revision) {
        String anchor = new LocationPathCodec().encode(ANCHOR);
        String contextEvidence = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, CONTEXT_ID, 3,
                        new MacOsApfsLocationContextEvidence(1, "macos-local-apfs", 1,
                                ANCHOR, LocationKeyCodec.encode(ANCHOR), "apfs", VOLUME_ID,
                                "2", true, false, 1,
                                MacOsApfsLocationContextEvidence.Diagnostics.empty())));
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 3, ?, 1, 20)
                """, CONTEXT_ID, anchor, LocationKeyCodec.encode(ANCHOR).value(), contextEvidence);
        String evidence = new SourceBindingEvidenceCodec().encode(new SourceBindingEvidence(1, 1,
                new MacOsApfsSourceRootEvidence(1, "macos-local-apfs-source-root", 1,
                        CONTEXT_ID, 3, revision, ROOT, LocationKeyCodec.encode(ROOT), VOLUME_ID,
                        "10", new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1)));
        jdbc.update("""
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    root_path_dialect, bound_location_context_id, binding_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (1, 'Photos', ?, ?, ?, 'unix', ?, ?, 1, 25)
                """, "/Volumes/Archive/Photos", LocationKeyCodec.encode(ROOT).value(),
                revision, CONTEXT_ID, evidence);
        Source source = sources.findSourceById(1).orElseThrow();
        periods.insertOpen(new SourceBindingPeriod(null, 1, revision, CONTEXT_ID,
                "unix", source.rootPath(), source.rootPathKey(), evidence, 25, null, null));
        return source;
    }

    private void insertRunSource(long id, String status) {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms, started_at_ms)
                VALUES (?, 'INDEX', 'RUNNING', 1, '{}', 1, 2)
                """, id);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation, started_at_ms)
                VALUES (?, ?, 1, ?, 5, 1, 2)
                """, id, id, status);
    }

    private void insertMembership(long id, String presence, long memberRevision) {
        jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (?, 12, 1)", id + 100);
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, current_content_id,
                    size_bytes, modified_time_epoch_second, modified_time_nano,
                    observation_revision, first_seen_at_ms, last_seen_at_ms)
                VALUES (?, 'UNRESOLVED', ?, 12, 123, 456, 2, 3, 4)
                """, id, id + 100);
        jdbc.update("""
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, membership_revision,
                    observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                    last_positive_scan_run_source_id, last_positive_traversal_generation,
                    observed_source_location_revision, observed_location_context_revision)
                VALUES (?, 1, ?, ?, ?, 'ACTIVE', ?, ?, 2, 3, 4, ?, 1, 5, 3)
                """, id, id, "file-" + id, "file-" + id, presence, memberRevision,
                scans.findScanRunSourceById(11).map(row -> row.id()).orElse(null));
        jdbc.update("""
                INSERT INTO analysis_record (id, content_record_id, analysis_type, analyzer_id,
                    analyzer_version, configuration_version, configuration_hash,
                    configuration_json, status, created_at_ms)
                VALUES (?, ?, 'CONTENT_HASH', 'builtin.sha256', '1', 1, 'hash', '{}', 'COMPLETED', 5)
                """, id + 200, id + 100);
        jdbc.update("""
                INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex)
                VALUES (?, 'SHA-256', ?)
                """, id + 200, "abcd" + id);
    }

    private ResolvedFileCandidate candidate() {
        return new ResolvedFileCandidate(1, 5, CONTEXT_ID, 3, FILE,
                LocationKeyCodec.encode(FILE), "a.jpg", "a.jpg", "apfs", VOLUME_ID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false, 12, 123, 456);
    }

    private MissingClaimAuthority claim() {
        return new MissingClaimAuthority(1, 5, CONTEXT_ID, 3, ROOT);
    }

    private RaceResults race(Attempt firstAttempt, Attempt secondAttempt) throws Exception {
        ReservationGate gate = new ReservationGate();
        hooks.gate = gate;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> attempt(firstAttempt));
            assertTrue(gate.firstReserved.await(10, TimeUnit.SECONDS));
            var second = callers.submit(() -> attempt(secondAttempt));
            try {
                assertTrue(gate.secondEntered.await(10, TimeUnit.SECONDS));
                assertFalse(gate.secondReserved.await(500, TimeUnit.MILLISECONDS));
                gate.releaseFirst.countDown();
                Object firstResult = first.get(15, TimeUnit.SECONDS);
                Object secondResult = second.get(15, TimeUnit.SECONDS);
                assertEquals(0, gate.secondReserved.getCount());
                return new RaceResults(firstResult, secondResult);
            } finally {
                gate.releaseFirst.countDown();
                hooks.gate = null;
            }
        }
    }

    private static Object attempt(Attempt attempt) {
        try {
            return attempt.run();
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private List<String> rows(String sql) {
        return jdbc.query(sql, (result, ignored) -> {
            StringBuilder value = new StringBuilder();
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                if (column > 1) value.append('|');
                value.append(result.getString(column));
            }
            return value.toString();
        });
    }

    private static LocationPath path(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }

    @FunctionalInterface
    private interface Attempt {
        Object run();
    }

    private record RaceResults(Object first, Object second) {
    }

    static class Hooks {
        volatile String failure;
        volatile ReservationGate gate;
    }

    static class ReservationGate {
        final AtomicReference<Thread> firstCaller = new AtomicReference<>();
        final CountDownLatch firstReserved = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondReserved = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureAndReservationHooks {
        @Bean Hooks hooks() { return new Hooks(); }

        @Bean @Primary
        LocationContextRepository coordinatedContext(JdbcTemplate jdbc, Hooks hooks) {
            return new LocationContextRepository(jdbc) {
                @Override public void reserveWrite() {
                    ReservationGate gate = hooks.gate;
                    if (gate == null) {
                        super.reserveWrite();
                    } else if (gate.firstCaller.compareAndSet(null, Thread.currentThread())) {
                        super.reserveWrite();
                        gate.firstReserved.countDown();
                        try {
                            if (!gate.releaseFirst.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("First writer was not released");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while reserving writer", exception);
                        }
                    } else {
                        gate.secondEntered.countDown();
                        super.reserveWrite();
                        gate.secondReserved.countDown();
                    }
                }
            };
        }

        @Bean @Primary
        SourceBindingPeriodRepository failingPeriods(JdbcTemplate jdbc, Hooks hooks) {
            return new SourceBindingPeriodRepository(jdbc) {
                @Override public int closeOpen(SourceBindingPeriod period, long revision, long time) {
                    return "period".equals(hooks.failure) ? 0 : super.closeOpen(period, revision, time);
                }
            };
        }

        @Bean @Primary
        SourceMembershipRepository failingMemberships(JdbcTemplate jdbc, Hooks hooks) {
            return new SourceMembershipRepository(jdbc) {
                @Override public int retireActiveBySourceId(long sourceId) {
                    return "memberships".equals(hooks.failure) ? 0 : super.retireActiveBySourceId(sourceId);
                }
            };
        }

        @Bean @Primary
        CatalogRepository failingSources(JdbcTemplate jdbc, Hooks hooks) {
            return new CatalogRepository(jdbc) {
                @Override public int unbindSource(Source source, long time) {
                    return "source".equals(hooks.failure) ? 0 : super.unbindSource(source, time);
                }
            };
        }
    }
}
