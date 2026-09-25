package io.github.topher6835.mediacompare.catalog;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbcTemplate;

    public CatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Source insert(Source source) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO source (
                        name, root_path, root_path_key, location_revision,
                        root_path_dialect, bound_location_context_id, binding_evidence_json,
                        created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, source.name());
            statement.setString(2, source.rootPath());
            statement.setString(3, source.rootPathKey());
            statement.setLong(4, source.locationRevision());
            statement.setString(5, source.rootPathDialect());
            statement.setString(6, source.boundLocationContextId());
            statement.setString(7, source.bindingEvidenceJson());
            statement.setLong(8, source.createdAtMs());
            statement.setLong(9, source.updatedAtMs());
            return statement;
        }, keyHolder);

        return new Source(generatedId(keyHolder), source.name(), source.rootPath(), source.rootPathKey(),
                source.locationRevision(), source.rootPathDialect(), source.boundLocationContextId(),
                source.bindingEvidenceJson(), source.createdAtMs(), source.updatedAtMs());
    }

    public Optional<Source> findSourceById(long id) {
        return jdbcTemplate.query("SELECT * FROM source WHERE id = ?", CatalogRepository::mapSource, id)
                .stream()
                .findFirst();
    }

    public int bindUnboundSource(long sourceId, long expectedRevision, String configuredRootPath,
            String rootLocationKey, String contextId, String bindingEvidenceJson, long boundAtMs) {
        return jdbcTemplate.update("""
                UPDATE source
                SET root_path_dialect = 'unix', root_path_key = ?,
                    bound_location_context_id = ?, binding_evidence_json = ?,
                    location_revision = ?, updated_at_ms = ?
                WHERE id = ? AND location_revision = ?
                    AND bound_location_context_id IS NULL
                    AND root_path_dialect IS NULL
                    AND binding_evidence_json IS NULL
                    AND root_path = ? AND root_path_key = ?
                """, rootLocationKey, contextId, bindingEvidenceJson,
                expectedRevision + 1, boundAtMs, sourceId, expectedRevision,
                configuredRootPath, configuredRootPath);
    }

    public int unbindSource(Source source, long unboundAtMs) {
        return jdbcTemplate.update("""
                UPDATE source
                SET bound_location_context_id = NULL, binding_evidence_json = NULL,
                    location_revision = ?, updated_at_ms = ?
                WHERE id = ? AND location_revision = ? AND updated_at_ms = ?
                  AND bound_location_context_id = ? AND binding_evidence_json = ?
                  AND root_path_dialect = ? AND root_path = ? AND root_path_key = ?
                """, source.locationRevision() + 1, unboundAtMs, source.id(),
                source.locationRevision(), source.updatedAtMs(), source.boundLocationContextId(),
                source.bindingEvidenceJson(), source.rootPathDialect(), source.rootPath(),
                source.rootPathKey());
    }

    public List<Source> findAllSources() {
        return jdbcTemplate.query("SELECT * FROM source ORDER BY id", CatalogRepository::mapSource);
    }

    public ContentRecord insert(ContentRecord contentRecord) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO content_record (size_bytes, created_at_ms) VALUES (?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, contentRecord.sizeBytes());
            statement.setLong(2, contentRecord.createdAtMs());
            return statement;
        }, keyHolder);

        return new ContentRecord(generatedId(keyHolder), contentRecord.sizeBytes(), contentRecord.createdAtMs());
    }

    public Optional<ContentRecord> findContentRecordById(long id) {
        return jdbcTemplate.query("SELECT * FROM content_record WHERE id = ?",
                (resultSet, rowNumber) -> new ContentRecord(
                        resultSet.getLong("id"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getLong("created_at_ms")),
                id).stream().findFirst();
    }

    public List<ContentAssignmentCandidate> findContentAssignmentCandidates(
            long scanRunSourceId, long completedGeneration, long afterFileEntryId, int limit) {
        return jdbcTemplate.query("""
                SELECT file_entry.id, membership.id AS membership_id,
                       membership.source_id, file_entry.location_context_id,
                       membership.observed_source_location_revision,
                       membership.observed_location_context_revision,
                       membership.membership_revision,
                       file_entry.observation_revision, file_entry.size_bytes
                FROM file_entry
                JOIN source_membership AS membership ON membership.file_entry_id = file_entry.id
                JOIN scan_run_source ON scan_run_source.id = ?
                    AND scan_run_source.source_id = membership.source_id
                JOIN source ON source.id = membership.source_id
                JOIN location_context AS context ON context.id = file_entry.location_context_id
                WHERE file_entry.location_identity_status = 'RESOLVED'
                  AND membership.applicability_status = 'ACTIVE'
                  AND membership.presence_status = 'PRESENT'
                  AND membership.observed_file_entry_revision = file_entry.observation_revision
                  AND membership.observed_source_location_revision = source.location_revision
                  AND membership.observed_location_context_revision = context.revision
                  AND source.bound_location_context_id = context.id
                  AND context.lifecycle_status = 'ACTIVE'
                  AND context.continuity_status = 'ACCEPTED'
                  AND file_entry.current_content_id IS NULL
                  AND membership.last_positive_scan_run_source_id = scan_run_source.id
                  AND membership.last_positive_traversal_generation = ?
                  AND file_entry.id > ?
                ORDER BY file_entry.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new ContentAssignmentCandidate(
                        resultSet.getLong("id"),
                        resultSet.getLong("membership_id"),
                        resultSet.getLong("source_id"),
                        resultSet.getString("location_context_id"),
                        resultSet.getLong("observed_source_location_revision"),
                        resultSet.getLong("observed_location_context_revision"),
                        resultSet.getLong("membership_revision"),
                        resultSet.getLong("observation_revision"),
                        resultSet.getLong("size_bytes")),
                scanRunSourceId, completedGeneration, afterFileEntryId, limit);
    }

    public int attachContentIfCurrent(ContentAssignmentCandidate candidate, long contentRecordId) {
        return jdbcTemplate.update("""
                UPDATE file_entry
                SET current_content_id = ?
                  WHERE id = ?
                  AND location_identity_status = 'RESOLVED'
                  AND location_context_id = ?
                  AND current_content_id IS NULL
                  AND observation_revision = ?
                  AND size_bytes = ?
                  AND EXISTS (
                      SELECT 1 FROM source_membership AS membership
                      JOIN source ON source.id = membership.source_id
                      JOIN location_context AS context ON context.id = file_entry.location_context_id
                      WHERE membership.id = ? AND membership.file_entry_id = file_entry.id
                        AND membership.source_id = ?
                        AND membership.applicability_status = 'ACTIVE'
                        AND membership.presence_status = 'PRESENT'
                        AND membership.membership_revision = ?
                        AND membership.observed_file_entry_revision = file_entry.observation_revision
                        AND membership.observed_source_location_revision = ?
                        AND membership.observed_location_context_revision = ?
                        AND source.location_revision = membership.observed_source_location_revision
                        AND source.bound_location_context_id = context.id
                        AND context.revision = membership.observed_location_context_revision
                        AND context.lifecycle_status = 'ACTIVE'
                        AND context.continuity_status = 'ACCEPTED'
                  )
                """, contentRecordId, candidate.fileEntryId(), candidate.contextId(),
                candidate.observationRevision(), candidate.sizeBytes(), candidate.membershipId(),
                candidate.sourceId(), candidate.membershipRevision(),
                candidate.sourceLocationRevision(), candidate.contextRevision());
    }

    public List<ContentHashCandidate> findContentHashCandidates(
            long scanRunSourceId, long completedGeneration, long afterFileEntryId, int limit) {
        return jdbcTemplate.query("""
                SELECT file_entry.id, file_entry.current_content_id,
                       membership.id AS membership_id, membership.source_id,
                       file_entry.location_context_id,
                       membership.observed_location_context_revision,
                       membership.membership_revision,
                       file_entry.location_path, file_entry.location_key,
                       file_entry.observation_revision, file_entry.size_bytes,
                       file_entry.modified_time_epoch_second, file_entry.modified_time_nano,
                       membership.observed_source_location_revision
                FROM file_entry
                JOIN source_membership AS membership ON membership.file_entry_id = file_entry.id
                JOIN scan_run_source ON scan_run_source.id = ?
                    AND scan_run_source.source_id = membership.source_id
                JOIN source ON source.id = membership.source_id
                JOIN location_context AS context ON context.id = file_entry.location_context_id
                WHERE file_entry.location_identity_status = 'RESOLVED'
                  AND membership.applicability_status = 'ACTIVE'
                  AND membership.presence_status = 'PRESENT'
                  AND membership.observed_file_entry_revision = file_entry.observation_revision
                  AND membership.observed_source_location_revision = source.location_revision
                  AND membership.observed_location_context_revision = context.revision
                  AND source.bound_location_context_id = context.id
                  AND context.lifecycle_status = 'ACTIVE'
                  AND context.continuity_status = 'ACCEPTED'
                  AND file_entry.current_content_id IS NOT NULL
                  AND membership.last_positive_scan_run_source_id = scan_run_source.id
                  AND membership.last_positive_traversal_generation = ?
                  AND file_entry.id > ?
                ORDER BY file_entry.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new ContentHashCandidate(
                        resultSet.getLong("id"),
                        resultSet.getLong("current_content_id"),
                        resultSet.getLong("membership_id"),
                        resultSet.getLong("source_id"),
                        resultSet.getString("location_context_id"),
                        resultSet.getLong("observed_location_context_revision"),
                        resultSet.getLong("membership_revision"),
                        resultSet.getString("location_path"),
                        resultSet.getString("location_key"),
                        resultSet.getLong("observation_revision"),
                        resultSet.getLong("size_bytes"),
                        nullableLong(resultSet, "modified_time_epoch_second"),
                        nullableInteger(resultSet, "modified_time_nano"),
                        resultSet.getLong("observed_source_location_revision")),
                scanRunSourceId, completedGeneration, afterFileEntryId, limit);
    }

    public int verifyContentHashCandidate(ContentHashCandidate candidate) {
        return jdbcTemplate.update("""
                UPDATE file_entry
                SET current_content_id = current_content_id
                WHERE id = ?
                  AND location_identity_status = 'RESOLVED'
                  AND location_context_id = ?
                  AND location_path = ? AND location_key = ?
                  AND current_content_id = ?
                  AND observation_revision = ?
                  AND size_bytes = ?
                  AND modified_time_epoch_second IS ?
                  AND modified_time_nano IS ?
                  AND EXISTS (
                      SELECT 1 FROM source_membership AS membership
                      JOIN source ON source.id = membership.source_id
                      JOIN location_context AS context ON context.id = file_entry.location_context_id
                      WHERE membership.id = ? AND membership.file_entry_id = file_entry.id
                        AND membership.source_id = ?
                        AND membership.applicability_status = 'ACTIVE'
                        AND membership.presence_status = 'PRESENT'
                        AND membership.membership_revision = ?
                        AND membership.observed_file_entry_revision = file_entry.observation_revision
                        AND membership.observed_source_location_revision = ?
                        AND membership.observed_location_context_revision = ?
                        AND source.location_revision = membership.observed_source_location_revision
                        AND source.bound_location_context_id = context.id
                        AND context.revision = membership.observed_location_context_revision
                        AND context.lifecycle_status = 'ACTIVE'
                        AND context.continuity_status = 'ACCEPTED'
                  )
                """,
                candidate.fileEntryId(),
                candidate.contextId(),
                candidate.locationPath(),
                candidate.locationKey(),
                candidate.contentRecordId(),
                candidate.observationRevision(),
                candidate.sizeBytes(),
                candidate.modifiedTimeEpochSecond(),
                candidate.modifiedTimeNano(),
                candidate.membershipId(), candidate.sourceId(), candidate.membershipRevision(),
                candidate.sourceLocationRevision(), candidate.contextRevision());
    }

    public FileEntry insert(FileEntry fileEntry) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO file_entry (
                        location_identity_status, location_context_id, location_path, location_key,
                        current_content_id, size_bytes, modified_time_epoch_second,
                        modified_time_nano, extension_key, observation_revision,
                        first_seen_at_ms, last_seen_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, fileEntry.locationIdentityStatus());
            statement.setString(2, fileEntry.locationContextId());
            statement.setString(3, fileEntry.locationPath());
            statement.setString(4, fileEntry.locationKey());
            setNullableLong(statement, 5, fileEntry.currentContentId());
            statement.setLong(6, fileEntry.sizeBytes());
            setNullableLong(statement, 7, fileEntry.modifiedTimeEpochSecond());
            setNullableInteger(statement, 8, fileEntry.modifiedTimeNano());
            statement.setString(9, fileEntry.extensionKey());
            statement.setLong(10, fileEntry.observationRevision());
            statement.setLong(11, fileEntry.firstSeenAtMs());
            statement.setLong(12, fileEntry.lastSeenAtMs());
            return statement;
        }, keyHolder);
        return new FileEntry(generatedId(keyHolder), fileEntry.locationIdentityStatus(),
                fileEntry.locationContextId(), fileEntry.locationPath(), fileEntry.locationKey(),
                fileEntry.currentContentId(), fileEntry.sizeBytes(), fileEntry.modifiedTimeEpochSecond(),
                fileEntry.modifiedTimeNano(), fileEntry.extensionKey(), fileEntry.observationRevision(),
                fileEntry.firstSeenAtMs(), fileEntry.lastSeenAtMs());
    }

    /** Historical discovery is incompatible with the V6 authority model. */
    public FileEntry observeFile(FileObservation observation) {
        throw new UnsupportedOperationException("Legacy Source-owned discovery cannot run after V6");
    }

    public Optional<FileEntry> findFileEntryById(long id) {
        return jdbcTemplate.query("SELECT * FROM file_entry WHERE id = ?", CatalogRepository::mapFileEntry, id)
                .stream().findFirst();
    }

    public Optional<FileEntry> findFileEntryBySourceIdAndPathKey(long sourceId, String pathKey) {
        return jdbcTemplate.query("""
                SELECT file_entry.* FROM file_entry
                JOIN source_membership AS membership ON membership.file_entry_id = file_entry.id
                WHERE membership.source_id = ? AND membership.path_key = ?
                  AND membership.applicability_status = 'ACTIVE'
                """, CatalogRepository::mapFileEntry, sourceId, pathKey).stream().findFirst();
    }

    /** Historical missing sweeps have no trusted V6 traversal authority. */
    public int markUnseenPresentFilesMissing(long sourceId, long scanRunSourceId, long traversalGeneration) {
        throw new UnsupportedOperationException("Legacy missing sweep cannot run after V6");
    }

    public WorkingSet insert(WorkingSet workingSet) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO working_set (name, created_at_ms, updated_at_ms) VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, workingSet.name());
            statement.setLong(2, workingSet.createdAtMs());
            statement.setLong(3, workingSet.updatedAtMs());
            return statement;
        }, keyHolder);

        return new WorkingSet(generatedId(keyHolder), workingSet.name(), workingSet.createdAtMs(),
                workingSet.updatedAtMs());
    }

    public Optional<WorkingSet> findWorkingSetById(long id) {
        return jdbcTemplate.query("SELECT * FROM working_set WHERE id = ?",
                (resultSet, rowNumber) -> new WorkingSet(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getLong("created_at_ms"),
                        resultSet.getLong("updated_at_ms")),
                id).stream().findFirst();
    }

    public void insert(WorkingSetContent membership) {
        jdbcTemplate.update("""
                INSERT INTO working_set_content (working_set_id, content_record_id, added_at_ms)
                VALUES (?, ?, ?)
                """, membership.workingSetId(), membership.contentRecordId(), membership.addedAtMs());
    }

    public Optional<WorkingSetContent> findWorkingSetContent(long workingSetId, long contentRecordId) {
        return jdbcTemplate.query("""
                SELECT * FROM working_set_content
                WHERE working_set_id = ? AND content_record_id = ?
                """, (resultSet, rowNumber) -> new WorkingSetContent(
                        resultSet.getLong("working_set_id"),
                        resultSet.getLong("content_record_id"),
                        resultSet.getLong("added_at_ms")),
                workingSetId, contentRecordId).stream().findFirst();
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        return Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
    }

    private static Source mapSource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Source(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("root_path"),
                resultSet.getString("root_path_key"),
                resultSet.getLong("location_revision"),
                resultSet.getString("root_path_dialect"),
                resultSet.getString("bound_location_context_id"),
                resultSet.getString("binding_evidence_json"),
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms"));
    }

    private static FileEntry mapFileEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FileEntry(
                resultSet.getLong("id"),
                resultSet.getString("location_identity_status"),
                resultSet.getString("location_context_id"),
                resultSet.getString("location_path"),
                resultSet.getString("location_key"),
                nullableLong(resultSet, "current_content_id"),
                resultSet.getLong("size_bytes"),
                nullableLong(resultSet, "modified_time_epoch_second"),
                nullableInteger(resultSet, "modified_time_nano"),
                resultSet.getString("extension_key"),
                resultSet.getLong("observation_revision"),
                resultSet.getLong("first_seen_at_ms"),
                resultSet.getLong("last_seen_at_ms"));
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }
}
