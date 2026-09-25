package io.github.topher6835.mediacompare.catalog;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Persistence for resolved occurrences and their sole Source/presence relationships. */
@Repository
public class SourceMembershipRepository {
    private final JdbcTemplate jdbc;

    public SourceMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FileEntry> findResolved(String contextId, String locationKey) {
        return jdbc.query("""
                SELECT * FROM file_entry
                WHERE location_identity_status = 'RESOLVED'
                  AND location_context_id = ? AND location_key = ?
                """, SourceMembershipRepository::fileEntry, contextId, locationKey)
                .stream().findFirst();
    }

    public FileEntry insertResolved(FileEntry entry) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO file_entry (
                        location_identity_status, location_context_id, location_path, location_key,
                        current_content_id, size_bytes, modified_time_epoch_second,
                        modified_time_nano, extension_key, observation_revision,
                        first_seen_at_ms, last_seen_at_ms
                    ) VALUES ('RESOLVED', ?, ?, ?, NULL, ?, ?, ?, ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, entry.locationContextId());
            statement.setString(2, entry.locationPath());
            statement.setString(3, entry.locationKey());
            statement.setLong(4, entry.sizeBytes());
            statement.setLong(5, entry.modifiedTimeEpochSecond());
            statement.setInt(6, entry.modifiedTimeNano());
            statement.setString(7, entry.extensionKey());
            statement.setLong(8, entry.firstSeenAtMs());
            statement.setLong(9, entry.lastSeenAtMs());
            return statement;
        }, keys);
        return findById(keys.getKey().longValue()).orElseThrow();
    }

    public Optional<FileEntry> findById(long id) {
        return jdbc.query("SELECT * FROM file_entry WHERE id = ?",
                SourceMembershipRepository::fileEntry, id).stream().findFirst();
    }

    public int refreshResolved(FileEntry prior, long size, long seconds, int nanos,
            long observedAtMs, boolean bytesChanged) {
        return jdbc.update("""
                UPDATE file_entry
                SET size_bytes = ?, modified_time_epoch_second = ?, modified_time_nano = ?,
                    current_content_id = CASE WHEN ? THEN NULL ELSE current_content_id END,
                    observation_revision = observation_revision + CASE WHEN ? THEN 1 ELSE 0 END,
                    last_seen_at_ms = ?
                WHERE id = ? AND location_identity_status = 'RESOLVED'
                  AND location_context_id = ? AND location_path = ? AND location_key = ?
                  AND observation_revision = ?
                  AND size_bytes = ? AND modified_time_epoch_second IS ? AND modified_time_nano IS ?
                """, size, seconds, nanos, bytesChanged, bytesChanged, observedAtMs,
                prior.id(), prior.locationContextId(), prior.locationPath(), prior.locationKey(),
                prior.observationRevision(), prior.sizeBytes(), prior.modifiedTimeEpochSecond(),
                prior.modifiedTimeNano());
    }

    public Optional<SourceMembership> findActiveAtPath(long sourceId, String pathKey) {
        return jdbc.query("""
                SELECT * FROM source_membership
                WHERE source_id = ? AND path_key = ? AND applicability_status = 'ACTIVE'
                """, SourceMembershipRepository::membership, sourceId, pathKey).stream().findFirst();
    }

    public Optional<SourceMembership> findBySourceAndFile(long sourceId, long fileEntryId) {
        return jdbc.query("""
                SELECT * FROM source_membership WHERE source_id = ? AND file_entry_id = ?
                """, SourceMembershipRepository::membership, sourceId, fileEntryId)
                .stream().findFirst();
    }

    public int retire(SourceMembership membership) {
        return jdbc.update("""
                UPDATE source_membership
                SET applicability_status = 'RETIRED', membership_revision = membership_revision + 1
                WHERE id = ? AND source_id = ? AND file_entry_id = ?
                  AND applicability_status = 'ACTIVE' AND membership_revision = ?
                """, membership.id(), membership.sourceId(), membership.fileEntryId(),
                membership.membershipRevision());
    }

    public SourceMembership insert(SourceMembership membership) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO source_membership (
                        source_id, file_entry_id, relative_path, path_key,
                        applicability_status, presence_status, membership_revision,
                        observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                        last_positive_scan_run_source_id, last_positive_traversal_generation,
                        observed_source_location_revision, observed_location_context_revision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, membership.sourceId());
            statement.setLong(2, membership.fileEntryId());
            statement.setString(3, membership.relativePath());
            statement.setString(4, membership.pathKey());
            statement.setString(5, membership.applicabilityStatus());
            statement.setString(6, membership.presenceStatus());
            statement.setLong(7, membership.membershipRevision());
            statement.setLong(8, membership.observedFileEntryRevision());
            statement.setLong(9, membership.firstSeenAtMs());
            statement.setLong(10, membership.lastSeenAtMs());
            statement.setObject(11, membership.lastPositiveScanRunSourceId());
            statement.setObject(12, membership.lastPositiveTraversalGeneration());
            statement.setObject(13, membership.observedSourceLocationRevision());
            statement.setObject(14, membership.observedLocationContextRevision());
            return statement;
        }, keys);
        return findMembershipById(keys.getKey().longValue()).orElseThrow();
    }

    public Optional<SourceMembership> findMembershipById(long id) {
        return jdbc.query("SELECT * FROM source_membership WHERE id = ?",
                SourceMembershipRepository::membership, id).stream().findFirst();
    }

    public int refreshMembership(SourceMembership prior, SourceMembership next) {
        return jdbc.update("""
                UPDATE source_membership
                SET relative_path = ?, path_key = ?, applicability_status = ?, presence_status = ?,
                    membership_revision = membership_revision + 1,
                    observed_file_entry_revision = ?, last_seen_at_ms = ?,
                    last_positive_scan_run_source_id = ?, last_positive_traversal_generation = ?,
                    observed_source_location_revision = ?, observed_location_context_revision = ?
                WHERE id = ? AND source_id = ? AND file_entry_id = ?
                  AND membership_revision = ?
                """, next.relativePath(), next.pathKey(), next.applicabilityStatus(),
                next.presenceStatus(), next.observedFileEntryRevision(), next.lastSeenAtMs(),
                next.lastPositiveScanRunSourceId(), next.lastPositiveTraversalGeneration(),
                next.observedSourceLocationRevision(), next.observedLocationContextRevision(),
                prior.id(), prior.sourceId(), prior.fileEntryId(), prior.membershipRevision());
    }

    public int markUnseenMissing(long sourceId, long scanRunSourceId, long generation) {
        return jdbc.update("""
                UPDATE source_membership
                SET presence_status = 'MISSING', membership_revision = membership_revision + 1
                WHERE source_id = ? AND applicability_status = 'ACTIVE'
                  AND presence_status = 'PRESENT'
                  AND (last_positive_scan_run_source_id IS NOT ?
                       OR last_positive_traversal_generation IS NOT ?)
                """, sourceId, scanRunSourceId, generation);
    }

    private static FileEntry fileEntry(ResultSet row, int ignored) throws SQLException {
        return new FileEntry(row.getLong("id"), row.getString("location_identity_status"),
                row.getString("location_context_id"), row.getString("location_path"),
                row.getString("location_key"), nullableLong(row, "current_content_id"),
                row.getLong("size_bytes"), nullableLong(row, "modified_time_epoch_second"),
                nullableInteger(row, "modified_time_nano"), row.getString("extension_key"),
                row.getLong("observation_revision"), row.getLong("first_seen_at_ms"),
                row.getLong("last_seen_at_ms"));
    }

    private static SourceMembership membership(ResultSet row, int ignored) throws SQLException {
        return new SourceMembership(row.getLong("id"), row.getLong("source_id"),
                row.getLong("file_entry_id"), row.getString("relative_path"),
                row.getString("path_key"), row.getString("applicability_status"),
                row.getString("presence_status"), row.getLong("membership_revision"),
                row.getLong("observed_file_entry_revision"), row.getLong("first_seen_at_ms"),
                row.getLong("last_seen_at_ms"), nullableLong(row, "last_positive_scan_run_source_id"),
                nullableLong(row, "last_positive_traversal_generation"),
                nullableLong(row, "observed_source_location_revision"),
                nullableLong(row, "observed_location_context_revision"));
    }

    private static Long nullableLong(ResultSet row, String name) throws SQLException {
        long value = row.getLong(name);
        return row.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet row, String name) throws SQLException {
        int value = row.getInt(name);
        return row.wasNull() ? null : value;
    }
}
