package io.github.topher6835.mediacompare.catalog;

import static io.github.topher6835.mediacompare.filesystem.ExfatReceiptTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataContentCandidate;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt;

class ExfatOccurrenceRepositoryTests {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private SourceMembershipRepository memberships;
    private CatalogRepository catalog;
    private ExfatOccurrenceRepository occurrences;
    private TransactionTemplate transaction;

    @BeforeEach
    void catalog() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("catalog.db")
                + "?foreign_keys=on&busy_timeout=5000");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        memberships = new SourceMembershipRepository(jdbc);
        catalog = new CatalogRepository(jdbc);
        occurrences = new ExfatOccurrenceRepository(jdbc, memberships);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void explicitInsertsMapBothFieldsAndKeepNativeAddressLookupSeparateFromHistory() {
        seedRoute();
        FileEntry first = transaction.execute(status -> insertOccurrence(TOKEN));
        FileEntry second = transaction.execute(status -> insertOccurrence("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        assertTrue(memberships.findResolved(CONTEXT, key(FILE)).isEmpty());
        assertTrue(occurrences.findNativeAddressConflict(CONTEXT, key(FILE)).isEmpty());
        assertEquals(first, occurrences.findByOccurrenceToken(TOKEN).orElseThrow());
        assertEquals(first, catalog.findFileEntryById(first.id()).orElseThrow());
        assertEquals(first, memberships.findById(first.id()).orElseThrow());
        assertEquals(List.of(first, second), occurrences.findHistoricalAtAddress(CONTEXT, key(FILE)));
        FileEntry nativeEntry = memberships.insertResolved(nativeEntry(null));
        assertEquals(nativeEntry, memberships.findResolved(CONTEXT, key(FILE)).orElseThrow());
        assertEquals(nativeEntry, occurrences.findNativeAddressConflict(CONTEXT, key(FILE)).orElseThrow());
        assertNull(nativeEntry.occurrenceToken());
        assertNull(nativeEntry.observationEvidenceJson());
        assertThrows(RuntimeException.class, () -> memberships.insertResolved(nativeEntry(null)));
        assertThrows(RuntimeException.class, () -> transaction.execute(status -> insertOccurrence(TOKEN)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class));
    }

    @Test
    void nativeRefreshCannotChangeHistoricalReceiptAndNativeRefreshStillWorks() {
        seedRoute();
        FileEntry historical = transaction.execute(status -> insertOccurrence(TOKEN));
        assertEquals(0, memberships.refreshResolved(historical, 43, 501, 0, 2000, true));
        assertEquals(historical, memberships.findById(historical.id()).orElseThrow());
        FileEntry nativeEntry = memberships.insertResolved(nativeEntry(null));
        assertEquals(1, memberships.refreshResolved(nativeEntry, 43, 501, 0, 2000, true));
        FileEntry refreshed = catalog.findFileEntryById(nativeEntry.id()).orElseThrow();
        assertEquals(1, refreshed.observationRevision());
        assertEquals(43, refreshed.sizeBytes());
        assertNull(refreshed.occurrenceToken());
        assertNull(refreshed.observationEvidenceJson());
    }

    @Test
    void lowercaseMembershipUsesFinalReceiptIdAndCoincidingRelativePathAndKey() {
        seedRoute();
        transaction.executeWithoutResult(status -> {
            var ids = occurrences.reserveIds(1);
            var receipt = receipt(TOKEN, ROOT, List.of(source(1, ids.membershipIds().getFirst(), ROOT)));
            FileEntry file = occurrences.insertOccurrence(entry(ids.fileEntryId(), receipt),
                    FileSystemProfile.EXFAT, FileSystemProfile.EXFAT);
            SourceMembership member = member(ids.membershipIds().getFirst(), file.id());
            assertEquals(member, occurrences.insertOccurrenceMembership(member));
            assertEquals(member, memberships.findMembershipById(member.id()).orElseThrow());
            assertThrows(IllegalArgumentException.class,
                    () -> occurrences.insertOccurrenceMembership(member(member.id() + 1, file.id())));
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership", Integer.class));
        assertEquals(1, occurrences.findHistoricalMembershipsAtAddress(CONTEXT, key(FILE)).size());
        jdbc.update("UPDATE source_membership SET presence_status='MISSING'");
        assertEquals("MISSING", occurrences.findHistoricalMembershipsAtAddress(CONTEXT, key(FILE)).getFirst().presenceStatus());
        jdbc.update("UPDATE source_membership SET applicability_status='RETIRED'");
        assertEquals("RETIRED", occurrences.findHistoricalMembershipsAtAddress(CONTEXT, key(FILE)).getFirst().applicabilityStatus());
    }

    @Test
    void mixedCaseMembershipPreservesExistingWindowsExactSpellingKey() {
        seedRoute();
        transaction.executeWithoutResult(status -> {
            FileEntry file = insertMixedCaseOccurrence();
            SourceMembership member = mixedCaseMember(1, file.id(), "Holiday/a.jpg");
            assertEquals(member, occurrences.insertOccurrenceMembership(member));
            assertEquals("Holiday/a.jpg", memberships.findMembershipById(member.id()).orElseThrow().relativePath());
            assertEquals("Holiday/a.jpg", memberships.findMembershipById(member.id()).orElseThrow().pathKey());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"holiday/a.jpg", "Holiday/A.JPG", "Holiday\\a.jpg", "Holiday/./a.jpg",
            "Holiday/other.jpg", ""})
    void incorrectOrNoncanonicalMembershipKeyIsRejectedBeforeInsertion(String pathKey) {
        seedRoute();
        transaction.executeWithoutResult(status -> {
            FileEntry file = insertMixedCaseOccurrence();
            assertThrows(IllegalArgumentException.class,
                    () -> occurrences.insertOccurrenceMembership(mixedCaseMember(1, file.id(), pathKey)));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership", Integer.class));
            assertDoesNotThrow(() -> occurrences.insertOccurrenceMembership(
                    mixedCaseMember(1, file.id(), "Holiday/a.jpg")));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceId", "membershipId", "relativePath", "membershipRevision",
            "observedFileEntryRevision", "sourceRevision", "contextRevision", "scanRunSourceId", "generation"})
    void receiptMembershipIdRevisionAndTraversalChecksRemainEnforced(String mismatch) {
        seedRoute();
        transaction.executeWithoutResult(status -> {
            FileEntry file = insertMixedCaseOccurrence();
            var invalid = new SourceMembership(
                    "membershipId".equals(mismatch) ? 2L : 1L,
                    "sourceId".equals(mismatch) ? 2 : 1, file.id(),
                    "relativePath".equals(mismatch) ? "holiday/a.jpg" : "Holiday/a.jpg",
                    "Holiday/a.jpg", "ACTIVE", "PRESENT",
                    "membershipRevision".equals(mismatch) ? 1 : 0,
                    "observedFileEntryRevision".equals(mismatch) ? 1 : 0, 1000, 1001,
                    "scanRunSourceId".equals(mismatch) ? 2L : 1L,
                    "generation".equals(mismatch) ? 2L : 1L,
                    "sourceRevision".equals(mismatch) ? 2L : 1L,
                    "contextRevision".equals(mismatch) ? 2L : 1L);
            assertThrows(IllegalArgumentException.class, () -> occurrences.insertOccurrenceMembership(invalid));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership", Integer.class));
            assertDoesNotThrow(() -> occurrences.insertOccurrenceMembership(
                    mixedCaseMember(1, file.id(), "Holiday/a.jpg")));
        });
    }

    @Test
    void allocationRequiresBoundWriterTransactionAndStartsAtOneWithoutProvisionalRows() {
        assertThrows(IllegalStateException.class, () -> occurrences.reserveIds(1));
        transaction.executeWithoutResult(status -> {
            var ids = occurrences.reserveIds(3);
            assertEquals(1, ids.fileEntryId());
            assertEquals(List.of(1L, 2L, 3L), ids.membershipIds());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership", Integer.class));
            assertThrows(IllegalArgumentException.class, () -> occurrences.reserveIds(0));
            assertThrows(IllegalArgumentException.class, () -> occurrences.reserveIds(1001));
        });
        transaction.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> occurrences.reserveIds(1)));
    }

    @Test
    void populatedMaximaAndRollbackRetryUseCheckedOrdinaryPrimaryKeys() {
        seedRoute();
        jdbc.update("""
                INSERT INTO file_entry (id,location_identity_status,size_bytes,first_seen_at_ms,last_seen_at_ms)
                VALUES (40,'UNRESOLVED',0,1,1)
                """);
        jdbc.update("""
                INSERT INTO source_membership (id,source_id,file_entry_id,relative_path,path_key,
                    applicability_status,presence_status,observed_file_entry_revision,first_seen_at_ms,last_seen_at_ms)
                VALUES (70,1,40,'old','old','RETIRED','PRESENT',0,1,1)
                """);
        transaction.executeWithoutResult(status -> {
            assertEquals(new ExfatOccurrenceRepository.ReservedIds(41, List.of(71L, 72L)), occurrences.reserveIds(2));
            FileEntry file = insertOccurrence(TOKEN);
            assertEquals(41, file.id());
            occurrences.insertOccurrenceMembership(member(71, file.id()));
            status.setRollbackOnly();
        });
        assertTrue(occurrences.findByOccurrenceToken(TOKEN).isEmpty());
        assertEquals(41, transaction.execute(status -> {
            FileEntry file = insertOccurrence(TOKEN);
            occurrences.insertOccurrenceMembership(member(71, file.id()));
            return file;
        }).id());
        assertEquals(42, transaction.execute(status -> occurrences.reserveIds(1)).fileEntryId());
    }

    @Test
    void rejectsFileAndMembershipSignedIntegerExhaustionBeforeAnyInsertion() {
        seedRoute();
        jdbc.update("""
                INSERT INTO file_entry (id,location_identity_status,size_bytes,first_seen_at_ms,last_seen_at_ms)
                VALUES (?, 'UNRESOLVED',0,1,1)
                """, Long.MAX_VALUE - 1);
        assertEquals(Long.MAX_VALUE, transaction.execute(status -> occurrences.reserveIds(1)).fileEntryId());
        jdbc.update("UPDATE file_entry SET id=?", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> transaction.execute(status -> occurrences.reserveIds(1)));
        jdbc.update("UPDATE file_entry SET id=1");
        jdbc.update("""
                INSERT INTO source_membership (id,source_id,file_entry_id,relative_path,path_key,
                    applicability_status,presence_status,observed_file_entry_revision,first_seen_at_ms,last_seen_at_ms)
                VALUES (?,1,1,'old','old','RETIRED','PRESENT',0,1,1)
                """, Long.MAX_VALUE - 1);
        assertEquals(List.of(Long.MAX_VALUE), transaction.execute(status -> occurrences.reserveIds(1)).membershipIds());
        assertThrows(ArithmeticException.class, () -> transaction.execute(status -> occurrences.reserveIds(2)));
        jdbc.update("UPDATE source_membership SET id=?", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> transaction.execute(status -> occurrences.reserveIds(1)));
    }

    @Test
    void twoConcurrentWriterAdmissionsSerializeBeforeReadingMaxima() throws Exception {
        seedRoute();
        jdbc.update("""
                INSERT INTO source (id,name,root_path,root_path_key,location_revision,root_path_dialect,
                    bound_location_context_id,binding_evidence_json,created_at_ms,updated_at_ms)
                VALUES (2,'Overlap','X:/photos','root',1,'win-drive',?,'{}',1,1)
                """, CONTEXT);
        jdbc.update("""
                INSERT INTO scan_run_source (id,scan_run_id,source_id,status,source_location_revision,
                    traversal_generation,completed_generation) VALUES (2,1,2,'COMPLETED',1,1,1)
                """);
        var reserved = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.execute(status -> {
                var ids = insertWithMembership(TOKEN, 1);
                reserved.countDown();
                await(release);
                return ids;
            }));
            assertTrue(reserved.await(5, TimeUnit.SECONDS));
            var second = workers.submit(() -> transaction.execute(status -> {
                secondStarted.countDown();
                return insertWithMembership("cccccccc-cccc-4ccc-8ccc-cccccccccccc", 2);
            }));
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertFalse(second.isDone());
            } finally { release.countDown(); }
            assertEquals(new ExfatOccurrenceRepository.ReservedIds(1, List.of(1L)), first.get(10, TimeUnit.SECONDS));
            assertEquals(new ExfatOccurrenceRepository.ReservedIds(2, List.of(2L)), second.get(10, TimeUnit.SECONDS));
            assertEquals(2, occurrences.findHistoricalAtAddress(CONTEXT, key(FILE)).size());
        } finally { release.countDown(); }
    }

    @Test
    void profilePrimitivesRejectNativeEvidenceExfatNullEvidenceAndMismatchedProfiles() {
        FileEntry tokenEntry = entry(1, receipt());
        FileEntry nativeEntry = nativeEntry(null);
        for (var nativeProfile : List.of(FileSystemProfile.NTFS, FileSystemProfile.APFS)) {
            assertDoesNotThrow(() -> OccurrenceProfileValidation.requireResolved(nativeProfile, nativeProfile, nativeEntry));
            assertThrows(IllegalArgumentException.class,
                    () -> OccurrenceProfileValidation.requireResolved(nativeProfile, nativeProfile, tokenEntry));
        }
        assertDoesNotThrow(() -> OccurrenceProfileValidation.requireResolved(FileSystemProfile.EXFAT,
                FileSystemProfile.EXFAT, tokenEntry));
        assertThrows(IllegalArgumentException.class, () -> OccurrenceProfileValidation.requireResolved(
                FileSystemProfile.EXFAT, FileSystemProfile.EXFAT, nativeEntry));
        assertThrows(IllegalArgumentException.class, () -> OccurrenceProfileValidation.requireResolved(
                FileSystemProfile.EXFAT, FileSystemProfile.NTFS, tokenEntry));
        assertThrows(IllegalArgumentException.class, () -> OccurrenceProfileValidation.requireResolved(
                FileSystemProfile.UNKNOWN, FileSystemProfile.UNKNOWN, nativeEntry));
        assertThrows(IllegalArgumentException.class, () -> catalog.insert(tokenEntry));
        assertThrows(IllegalArgumentException.class, () -> memberships.insertResolved(tokenEntry));
        seedRoute();
        transaction.executeWithoutResult(status -> assertThrows(IllegalArgumentException.class,
                () -> occurrences.insertOccurrence(tokenEntry, FileSystemProfile.NTFS, FileSystemProfile.EXFAT)));
        FileEntry disagreeing = new FileEntry(1L, "RESOLVED", CONTEXT, tokenEntry.locationPath(), tokenEntry.locationKey(),
                null, 43, 500L, 123456700, "jpg", 0, 1000, 1001, TOKEN, tokenEntry.observationEvidenceJson());
        assertThrows(IllegalArgumentException.class, () -> OccurrenceProfileValidation.requireExfat(disagreeing));
    }

    @Test
    void nativeAssignmentHashAndOriginalReadCandidateQueriesExcludeTokenRows() {
        seedRoute();
        FileEntry file = transaction.execute(status -> {
            var ids = occurrences.reserveIds(1);
            var value = receipt(TOKEN, ROOT, List.of(source(1, ids.membershipIds().getFirst(), ROOT)));
            FileEntry stored = occurrences.insertOccurrence(entry(ids.fileEntryId(), value),
                    FileSystemProfile.EXFAT, FileSystemProfile.EXFAT);
            occurrences.insertOccurrenceMembership(member(ids.membershipIds().getFirst(), stored.id()));
            return stored;
        });
        assertEquals(List.of(), catalog.findContentAssignmentCandidates(1, 1, 0, 10));
        var assignment = new ContentAssignmentCandidate(file.id(), 1, 1, CONTEXT, 1, 1, 0, 0, 42);
        jdbc.update("INSERT INTO content_record (id,size_bytes,created_at_ms) VALUES (11,42,1)");
        assertEquals(0, catalog.attachContentIfCurrent(assignment, 11));
        jdbc.update("UPDATE file_entry SET current_content_id=11 WHERE id=?", file.id());
        assertEquals(List.of(), catalog.findContentHashCandidates(1, 1, 0, 10));
        var hashCandidate = new ContentHashCandidate(file.id(), 11, 1, 1, CONTEXT, 1, 0,
                file.locationPath(), file.locationKey(), 0, 42, 500L, 123456700, 1);
        assertEquals(0, catalog.verifyContentHashCandidate(hashCandidate));
        var metadata = new MediaMetadataCandidateRepository(jdbc);
        assertEquals(List.of(), metadata.findOccurrences(new MediaMetadataContentCandidate(11, 42), 0, 10));
        assertTrue(metadata.findCurrentOccurrence(file.id()).isEmpty());
    }

    private FileEntry insertOccurrence(String token) {
        var ids = occurrences.reserveIds(1);
        var value = receipt(token, ROOT, List.of(source(1, ids.membershipIds().getFirst(), ROOT)));
        return occurrences.insertOccurrence(entry(ids.fileEntryId(), value), FileSystemProfile.EXFAT, FileSystemProfile.EXFAT);
    }

    private FileEntry insertMixedCaseOccurrence() {
        var ids = occurrences.reserveIds(1);
        var source = source(1, ids.membershipIds().getFirst(), ROOT);
        var mixedCaseSource = new ExfatObservationReceipt.SourceEntry(source.sourceId(), source.sourceRevision(),
                source.rootPath(), source.rootPathKey(), source.windowUuid(), source.membershipId(),
                source.membershipRevision(), source.scanRunSourceId(), source.generation(), "Holiday/a.jpg");
        var value = receipt(TOKEN, ROOT.append("Holiday"), List.of(mixedCaseSource));
        return occurrences.insertOccurrence(entry(ids.fileEntryId(), value), FileSystemProfile.EXFAT, FileSystemProfile.EXFAT);
    }

    private static SourceMembership mixedCaseMember(long id, long fileId, String pathKey) {
        return new SourceMembership(id, 1, fileId, "Holiday/a.jpg", pathKey, "ACTIVE", "PRESENT", 0,
                0, 1000, 1001, 1L, 1L, 1L, 1L);
    }

    private ExfatOccurrenceRepository.ReservedIds insertWithMembership(String token, long sourceId) {
        var ids = occurrences.reserveIds(1);
        var value = receipt(token, ROOT, List.of(source(sourceId, ids.membershipIds().getFirst(), ROOT)));
        FileEntry file = occurrences.insertOccurrence(entry(ids.fileEntryId(), value), FileSystemProfile.EXFAT, FileSystemProfile.EXFAT);
        occurrences.insertOccurrenceMembership(new SourceMembership(ids.membershipIds().getFirst(), sourceId,
                file.id(), "a.jpg", "a.jpg", "ACTIVE", "PRESENT", 0, 0, 1000, 1001, sourceId, 1L, 1L, 1L));
        return ids;
    }

    private static FileEntry nativeEntry(Long id) {
        var value = receipt();
        return new FileEntry(id, "RESOLVED", CONTEXT, value.locationPath(), value.locationKey(), null,
                42, 500L, 123456700, "jpg", 0, 1000, 1001);
    }

    private static SourceMembership member(long id, long fileId) {
        return new SourceMembership(id, 1, fileId, "a.jpg", "a.jpg", "ACTIVE", "PRESENT", 0,
                0, 1000, 1001, 1L, 1L, 1L, 1L);
    }

    private void seedRoute() {
        jdbc.update("""
                INSERT INTO location_context (id,anchor_location_path,anchor_location_key,lifecycle_status,
                    continuity_status,revision,continuity_evidence_json,created_at_ms,updated_at_ms)
                VALUES (?,'anchor','key','ACTIVE','ACCEPTED',1,'{}',1,1)
                """, CONTEXT);
        jdbc.update("""
                INSERT INTO source (id,name,root_path,root_path_key,location_revision,root_path_dialect,
                    bound_location_context_id,binding_evidence_json,created_at_ms,updated_at_ms)
                VALUES (1,'Test','X:/photos','root',1,'win-drive',?,'{}',1,1)
                """, CONTEXT);
        jdbc.update("INSERT INTO scan_run (id,request_type,status,options_version,options_json,created_at_ms) VALUES (1,'INDEX','RUNNING',1,'{}',1)");
        jdbc.update("""
                INSERT INTO scan_run_source (id,scan_run_id,source_id,status,source_location_revision,
                    traversal_generation,completed_generation) VALUES (1,1,1,'COMPLETED',1,1,1)
                """);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer synchronization timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new AssertionError(failure);
        }
    }
}
