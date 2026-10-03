package io.github.topher6835.mediacompare.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.scan.IndexingRunService;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Occurrence persistence used only beneath the exact runtime publication gate. */
@Repository
public class ExfatOccurrenceRepository {
    private final JdbcTemplate jdbc;
    private final SourceMembershipRepository memberships;

    public ExfatOccurrenceRepository(JdbcTemplate jdbc, SourceMembershipRepository memberships) {
        this.jdbc = jdbc;
        this.memberships = memberships;
    }

    public Optional<FileEntry> findByOccurrenceToken(String token) {
        var entries = jdbc.query("SELECT * FROM file_entry WHERE occurrence_token = ?",
                SourceMembershipRepository::fileEntry, token);
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
    }

    public List<FileEntry> findHistoricalAtAddress(String contextId, String key) {
        return jdbc.query("""
                SELECT * FROM file_entry WHERE location_identity_status = 'RESOLVED'
                    AND occurrence_token IS NOT NULL AND location_context_id = ? AND location_key = ?
                ORDER BY id
                """, SourceMembershipRepository::fileEntry, contextId, key);
    }

    /** All applicability/presence states, including ACTIVE MISSING routes needed by later supersession. */
    public List<SourceMembership> findHistoricalMembershipsAtAddress(String contextId, String key) {
        return jdbc.query("""
                SELECT membership.* FROM file_entry
                JOIN source_membership AS membership ON membership.file_entry_id = file_entry.id
                WHERE file_entry.location_identity_status = 'RESOLVED' AND file_entry.occurrence_token IS NOT NULL
                    AND file_entry.location_context_id = ? AND file_entry.location_key = ?
                ORDER BY file_entry.id, membership.id
                """, SourceMembershipRepository::membership, contextId, key);
    }

    public Optional<FileEntry> findNativeAddressConflict(String contextId, String key) {
        return memberships.findResolved(contextId, key);
    }

    /** Page all active resolved routes, including native contradictions and ACTIVE/MISSING history. */
    public List<FileEntry> activeRoutes(String contextId, long afterId, int limit) {
        return jdbc.query("""
                SELECT * FROM file_entry f WHERE f.location_identity_status = 'RESOLVED'
                  AND f.location_context_id = ? AND f.id > ?
                  AND (f.occurrence_token IS NULL OR EXISTS (SELECT 1 FROM source_membership m WHERE m.file_entry_id = f.id
                    AND m.applicability_status = 'ACTIVE')) ORDER BY f.id LIMIT ?
                """, SourceMembershipRepository::fileEntry, contextId, afterId, limit);
    }

    public void retireOccurrence(FileEntry file) {
        requireWriterTransaction();
        OccurrenceProfileValidation.requireExfat(file);
        var active = jdbc.query("SELECT * FROM source_membership WHERE file_entry_id = ? AND applicability_status = 'ACTIVE' ORDER BY id",
                SourceMembershipRepository::membership, file.id());
        for (var member : active) retireChecked(member);
    }

    public void retireChecked(SourceMembership member) {
        requireWriterTransaction();
        Math.incrementExact(member.membershipRevision());
        if (memberships.retire(member) != 1) throw new IllegalStateException("Superseded membership changed");
    }

    public int markUnseenMissing(long sourceId, String contextId, long rowId, long generation) {
        requireWriterTransaction();
        long after = 0;
        while (true) {
            var page = jdbc.query("""
                    SELECT f.* FROM file_entry f JOIN source_membership m ON m.file_entry_id = f.id
                    WHERE m.source_id = ? AND m.applicability_status = 'ACTIVE'
                      AND f.location_identity_status = 'RESOLVED' AND f.id > ? ORDER BY f.id LIMIT 250
                    """, SourceMembershipRepository::fileEntry, sourceId, after);
            if (page.isEmpty()) break;
            for (var file : page) {
                after = file.id();
                var receipt = OccurrenceProfileValidation.requireExfat(file);
                if (!contextId.equals(receipt.contextId())) throw new IllegalStateException("Missing sweep has a foreign occurrence profile/context");
            }
        }
        Integer overflow = jdbc.queryForObject("""
                SELECT COUNT(*) FROM source_membership WHERE source_id = ? AND applicability_status = 'ACTIVE'
                  AND presence_status = 'PRESENT' AND membership_revision = 9223372036854775807
                  AND (last_positive_scan_run_source_id IS NOT ? OR last_positive_traversal_generation IS NOT ?)
                """, Integer.class, sourceId, rowId, generation);
        if (overflow != 0) throw new ArithmeticException("Missing membership revision overflow");
        return memberships.markUnseenMissing(sourceId, rowId, generation);
    }

    /** Include malformed/stale occurrences for validation rather than silently filtering them out. */
    public List<FileEntry> scanOccurrences(long scanRunId, List<Long> exfatSourceIds, long afterId, int limit) {
        String participating = exfatSourceIds.isEmpty() ? "NULL" : String.join(",", java.util.Collections.nCopies(exfatSourceIds.size(), "?"));
        String sql = """
                SELECT * FROM file_entry f WHERE f.id > ? AND EXISTS (
                  SELECT 1 FROM source_membership m JOIN scan_run_source r
                    ON r.id = m.last_positive_scan_run_source_id
                  WHERE m.file_entry_id = f.id AND r.scan_run_id = ?
                    AND (f.occurrence_token IS NOT NULL OR f.observation_evidence_json IS NOT NULL
                         OR r.source_id IN (%s)))
                ORDER BY f.id LIMIT ?
                """.formatted(participating);
        var arguments = new ArrayList<Object>();
        arguments.add(afterId); arguments.add(scanRunId); arguments.addAll(exfatSourceIds); arguments.add(limit);
        return jdbc.query(sql, SourceMembershipRepository::fileEntry, arguments.toArray());
    }

    public void attachContent(FileEntry file, long contentId) {
        requireWriterTransaction();
        if (jdbc.update("""
                UPDATE file_entry SET current_content_id = ? WHERE id = ? AND current_content_id IS NULL
                  AND occurrence_token = ? AND observation_revision = ? AND observation_evidence_json = ?
                """, contentId, file.id(), file.occurrenceToken(), file.observationRevision(), file.observationEvidenceJson()) != 1) {
            throw new IllegalStateException("Occurrence changed during content assignment");
        }
    }

    /** Reserve SQLite's writer before reading maxima; caller must retain this same transaction through insertion. */
    public ReservedIds reserveIds(int membershipCount) {
        requireWriterTransaction();
        if (membershipCount < 1 || membershipCount > IndexingRunService.MAX_SOURCE_IDS) {
            throw new IllegalArgumentException("Invalid participating Source count");
        }
        // Same reservation used by LocationContextRepository. Repeating it is harmless under an existing writer.
        jdbc.update("UPDATE location_context SET id = id WHERE 0");
        long fileId = nextId("SELECT MAX(id) FROM file_entry");
        long firstMembershipId = nextId("SELECT MAX(id) FROM source_membership");
        Math.addExact(firstMembershipId, membershipCount - 1L);
        var ids = new ArrayList<Long>(membershipCount);
        for (int i = 0; i < membershipCount; i++) ids.add(Math.addExact(firstMembershipId, i));
        return new ReservedIds(fileId, ids);
    }

    public FileEntry insertOccurrence(FileEntry entry, FileSystemProfile contextProfile,
            FileSystemProfile sourceProfile) {
        requireWriterTransaction();
        if (contextProfile != FileSystemProfile.EXFAT || sourceProfile != FileSystemProfile.EXFAT) {
            throw new IllegalArgumentException("exFAT storage requires exFAT context and Source profiles");
        }
        OccurrenceProfileValidation.requireResolved(contextProfile, sourceProfile, entry);
        if (entry.id() == null || entry.id() <= 0 || entry.observationRevision() != 0
                || entry.currentContentId() != null) {
            throw new IllegalArgumentException("New occurrence requires explicit positive ID, revision zero and no content");
        }
        jdbc.update("""
                INSERT INTO file_entry (id, location_identity_status, location_context_id, location_path,
                    location_key, current_content_id, size_bytes, modified_time_epoch_second, modified_time_nano,
                    extension_key, observation_revision, first_seen_at_ms, last_seen_at_ms,
                    occurrence_token, observation_evidence_json)
                VALUES (?, 'RESOLVED', ?, ?, ?, NULL, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """, entry.id(), entry.locationContextId(), entry.locationPath(), entry.locationKey(),
                entry.sizeBytes(), entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano(), entry.extensionKey(),
                entry.firstSeenAtMs(), entry.lastSeenAtMs(), entry.occurrenceToken(), entry.observationEvidenceJson());
        return memberships.findById(entry.id()).orElseThrow();
    }

    public SourceMembership insertOccurrenceMembership(SourceMembership member) {
        requireWriterTransaction();
        if (member.id() == null || member.id() <= 0 || !"ACTIVE".equals(member.applicabilityStatus())
                || !"PRESENT".equals(member.presenceStatus()) || member.membershipRevision() != 0) {
            throw new IllegalArgumentException("New occurrence membership requires explicit ID and initial state");
        }
        FileEntry entry = memberships.findById(member.fileEntryId()).orElseThrow();
        var receipt = OccurrenceProfileValidation.requireExfat(entry);
        var source = receipt.sources().stream().filter(route -> route.sourceId() == member.sourceId())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Source absent from receipt"));
        var paths = new LocationPathCodec();
        // Reuse native publication's exact-spelling key rule, including Windows component case.
        String canonicalPathKey = SourceRelativePath.from(
                paths.decode(source.rootPath()), paths.decode(receipt.locationPath()));
        if (source.membershipId() != member.id() || source.membershipRevision() != member.membershipRevision()
                || member.observedFileEntryRevision() != entry.observationRevision()
                || !source.relativeRoute().equals(member.relativePath())
                || !canonicalPathKey.equals(member.pathKey())
                || !Long.valueOf(source.sourceRevision()).equals(member.observedSourceLocationRevision())
                || !Long.valueOf(receipt.contextRevision()).equals(member.observedLocationContextRevision())
                || !Long.valueOf(source.scanRunSourceId()).equals(member.lastPositiveScanRunSourceId())
                || !Long.valueOf(source.generation()).equals(member.lastPositiveTraversalGeneration())) {
            throw new IllegalArgumentException("Membership disagrees with receipt");
        }
        jdbc.update("""
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path, path_key,
                    applicability_status, presence_status, membership_revision, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms, last_positive_scan_run_source_id,
                    last_positive_traversal_generation, observed_source_location_revision,
                    observed_location_context_revision)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'PRESENT', 0, ?, ?, ?, ?, ?, ?, ?)
                """, member.id(), member.sourceId(), member.fileEntryId(), member.relativePath(), member.pathKey(),
                member.observedFileEntryRevision(), member.firstSeenAtMs(), member.lastSeenAtMs(),
                member.lastPositiveScanRunSourceId(), member.lastPositiveTraversalGeneration(),
                member.observedSourceLocationRevision(), member.observedLocationContextRevision());
        return memberships.findMembershipById(member.id()).orElseThrow();
    }

    private long nextId(String query) {
        Long maximum = jdbc.queryForObject(query, Long.class);
        return maximum == null || maximum < 1 ? 1 : Math.addExact(maximum, 1);
    }

    private void requireWriterTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("exFAT storage requires the caller's catalog writer transaction");
        }
    }

    public record ReservedIds(long fileEntryId, List<Long> membershipIds) {
        public ReservedIds { membershipIds = List.copyOf(membershipIds); }
    }
}
