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
                        name, root_path, root_path_key, location_revision, created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, source.name());
            statement.setString(2, source.rootPath());
            statement.setString(3, source.rootPathKey());
            statement.setLong(4, source.locationRevision());
            statement.setLong(5, source.createdAtMs());
            statement.setLong(6, source.updatedAtMs());
            return statement;
        }, keyHolder);

        return new Source(generatedId(keyHolder), source.name(), source.rootPath(), source.rootPathKey(),
                source.locationRevision(), source.createdAtMs(), source.updatedAtMs());
    }

    public Optional<Source> findSourceById(long id) {
        return jdbcTemplate.query("SELECT * FROM source WHERE id = ?", CatalogRepository::mapSource, id)
                .stream()
                .findFirst();
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
                SELECT file_entry.id, file_entry.observation_revision, file_entry.size_bytes
                FROM file_entry
                JOIN scan_run_source
                  ON scan_run_source.id = ?
                 AND scan_run_source.source_id = file_entry.source_id
                WHERE file_entry.presence_status = 'PRESENT'
                  AND file_entry.current_content_id IS NULL
                  AND file_entry.last_seen_scan_run_source_id = scan_run_source.id
                  AND file_entry.last_seen_traversal_generation = ?
                  AND file_entry.id > ?
                ORDER BY file_entry.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new ContentAssignmentCandidate(
                        resultSet.getLong("id"),
                        resultSet.getLong("observation_revision"),
                        resultSet.getLong("size_bytes")),
                scanRunSourceId, completedGeneration, afterFileEntryId, limit);
    }

    public int attachContentIfCurrent(long fileEntryId, long expectedObservationRevision,
            long expectedSizeBytes, long contentRecordId) {
        return jdbcTemplate.update("""
                UPDATE file_entry
                SET current_content_id = ?
                WHERE id = ?
                  AND presence_status = 'PRESENT'
                  AND current_content_id IS NULL
                  AND observation_revision = ?
                  AND size_bytes = ?
                """, contentRecordId, fileEntryId, expectedObservationRevision, expectedSizeBytes);
    }

    public List<ContentHashCandidate> findContentHashCandidates(
            long scanRunSourceId, long completedGeneration, long afterFileEntryId, int limit) {
        return jdbcTemplate.query("""
                SELECT file_entry.id, file_entry.current_content_id, file_entry.source_id,
                       file_entry.relative_path, file_entry.observation_revision,
                       file_entry.size_bytes, file_entry.modified_time_epoch_second,
                       file_entry.modified_time_nano, scan_run_source.source_location_revision
                FROM file_entry
                JOIN scan_run_source
                  ON scan_run_source.id = ?
                 AND scan_run_source.source_id = file_entry.source_id
                WHERE file_entry.presence_status = 'PRESENT'
                  AND file_entry.current_content_id IS NOT NULL
                  AND file_entry.last_seen_scan_run_source_id = scan_run_source.id
                  AND file_entry.last_seen_traversal_generation = ?
                  AND file_entry.id > ?
                ORDER BY file_entry.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new ContentHashCandidate(
                        resultSet.getLong("id"),
                        resultSet.getLong("current_content_id"),
                        resultSet.getLong("source_id"),
                        resultSet.getString("relative_path"),
                        resultSet.getLong("observation_revision"),
                        resultSet.getLong("size_bytes"),
                        nullableLong(resultSet, "modified_time_epoch_second"),
                        nullableInteger(resultSet, "modified_time_nano"),
                        resultSet.getLong("source_location_revision")),
                scanRunSourceId, completedGeneration, afterFileEntryId, limit);
    }

    public int verifyContentHashCandidate(ContentHashCandidate candidate) {
        return jdbcTemplate.update("""
                UPDATE file_entry
                SET current_content_id = current_content_id
                WHERE id = ?
                  AND source_id = ?
                  AND presence_status = 'PRESENT'
                  AND current_content_id = ?
                  AND observation_revision = ?
                  AND size_bytes = ?
                  AND modified_time_epoch_second IS ?
                  AND modified_time_nano IS ?
                  AND EXISTS (
                      SELECT 1
                      FROM source
                      WHERE source.id = ? AND source.location_revision = ?
                  )
                """,
                candidate.fileEntryId(),
                candidate.sourceId(),
                candidate.contentRecordId(),
                candidate.observationRevision(),
                candidate.sizeBytes(),
                candidate.modifiedTimeEpochSecond(),
                candidate.modifiedTimeNano(),
                candidate.sourceId(),
                candidate.sourceLocationRevision());
    }

    @Transactional
    public FileEntry insert(FileEntry fileEntry) {
        validateLastSeenSource(fileEntry);

        return insertFileEntry(fileEntry);
    }

    public FileEntry observeFile(FileObservation observation) {
        validateLastSeenSource(observation.sourceId(), observation.scanRunSourceId());

        Optional<FileEntry> existingEntry = findFileEntryBySourceIdAndPathKey(
                observation.sourceId(), observation.pathKey());
        if (existingEntry.isEmpty()) {
            return insertFileEntry(new FileEntry(
                    null,
                    observation.sourceId(),
                    observation.relativePath(),
                    observation.pathKey(),
                    null,
                    "PRESENT",
                    observation.sizeBytes(),
                    observation.modifiedTimeEpochSecond(),
                    observation.modifiedTimeNano(),
                    0,
                    observation.observedAtMs(),
                    observation.observedAtMs(),
                    observation.scanRunSourceId(),
                    observation.traversalGeneration()));
        }

        FileEntry existing = existingEntry.orElseThrow();
        boolean bytesMayHaveChanged = existing.sizeBytes() != observation.sizeBytes()
                || !Objects.equals(existing.modifiedTimeEpochSecond(), observation.modifiedTimeEpochSecond())
                || !Objects.equals(existing.modifiedTimeNano(), observation.modifiedTimeNano())
                || !"PRESENT".equals(existing.presenceStatus());

        FileEntry updated = new FileEntry(
                existing.id(),
                existing.sourceId(),
                observation.relativePath(),
                existing.pathKey(),
                bytesMayHaveChanged ? null : existing.currentContentId(),
                "PRESENT",
                observation.sizeBytes(),
                observation.modifiedTimeEpochSecond(),
                observation.modifiedTimeNano(),
                bytesMayHaveChanged ? existing.observationRevision() + 1 : existing.observationRevision(),
                existing.firstSeenAtMs(),
                observation.observedAtMs(),
                observation.scanRunSourceId(),
                observation.traversalGeneration());
        updateObservedFileEntry(updated);
        return updated;
    }

    private FileEntry insertFileEntry(FileEntry fileEntry) {

        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO file_entry (
                        source_id, relative_path, path_key, current_content_id, presence_status, size_bytes,
                        modified_time_epoch_second, modified_time_nano, observation_revision,
                        first_seen_at_ms, last_seen_at_ms, last_seen_scan_run_source_id,
                        last_seen_traversal_generation
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, fileEntry.sourceId());
            statement.setString(2, fileEntry.relativePath());
            statement.setString(3, fileEntry.pathKey());
            setNullableLong(statement, 4, fileEntry.currentContentId());
            statement.setString(5, fileEntry.presenceStatus());
            statement.setLong(6, fileEntry.sizeBytes());
            setNullableLong(statement, 7, fileEntry.modifiedTimeEpochSecond());
            setNullableInteger(statement, 8, fileEntry.modifiedTimeNano());
            statement.setLong(9, fileEntry.observationRevision());
            statement.setLong(10, fileEntry.firstSeenAtMs());
            statement.setLong(11, fileEntry.lastSeenAtMs());
            setNullableLong(statement, 12, fileEntry.lastSeenScanRunSourceId());
            setNullableLong(statement, 13, fileEntry.lastSeenTraversalGeneration());
            return statement;
        }, keyHolder);

        return new FileEntry(generatedId(keyHolder), fileEntry.sourceId(), fileEntry.relativePath(),
                fileEntry.pathKey(), fileEntry.currentContentId(), fileEntry.presenceStatus(), fileEntry.sizeBytes(),
                fileEntry.modifiedTimeEpochSecond(), fileEntry.modifiedTimeNano(), fileEntry.observationRevision(),
                fileEntry.firstSeenAtMs(), fileEntry.lastSeenAtMs(), fileEntry.lastSeenScanRunSourceId(),
                fileEntry.lastSeenTraversalGeneration());
    }

    private void validateLastSeenSource(FileEntry fileEntry) {
        if (fileEntry.lastSeenScanRunSourceId() == null) {
            return;
        }

        validateLastSeenSource(fileEntry.sourceId(), fileEntry.lastSeenScanRunSourceId());
    }

    private void validateLastSeenSource(long sourceId, long scanRunSourceId) {

        Integer matchingRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM scan_run_source
                WHERE id = ? AND source_id = ?
                """, Integer.class, scanRunSourceId, sourceId);

        if (matchingRows == null || matchingRows != 1) {
            throw new DataIntegrityViolationException(
                    "FileEntry Source " + sourceId
                            + " does not match ScanRunSource " + scanRunSourceId);
        }
    }

    public Optional<FileEntry> findFileEntryById(long id) {
        return jdbcTemplate.query("SELECT * FROM file_entry WHERE id = ?", CatalogRepository::mapFileEntry, id)
                .stream()
                .findFirst();
    }

    public Optional<FileEntry> findFileEntryBySourceIdAndPathKey(long sourceId, String pathKey) {
        return jdbcTemplate.query("""
                SELECT * FROM file_entry
                WHERE source_id = ? AND path_key = ?
                """, CatalogRepository::mapFileEntry, sourceId, pathKey).stream().findFirst();
    }

    public int markUnseenPresentFilesMissing(long sourceId, long scanRunSourceId, long traversalGeneration) {
        return jdbcTemplate.update("""
                UPDATE file_entry
                SET presence_status = 'MISSING'
                WHERE source_id = ?
                  AND presence_status = 'PRESENT'
                  AND (
                      last_seen_scan_run_source_id IS NULL
                      OR last_seen_traversal_generation IS NULL
                      OR last_seen_scan_run_source_id <> ?
                      OR last_seen_traversal_generation <> ?
                  )
                """, sourceId, scanRunSourceId, traversalGeneration);
    }

    private void updateObservedFileEntry(FileEntry fileEntry) {
        jdbcTemplate.update("""
                UPDATE file_entry
                SET relative_path = ?, current_content_id = ?, presence_status = ?, size_bytes = ?,
                    modified_time_epoch_second = ?, modified_time_nano = ?, observation_revision = ?,
                    last_seen_at_ms = ?, last_seen_scan_run_source_id = ?, last_seen_traversal_generation = ?
                WHERE id = ?
                """,
                fileEntry.relativePath(),
                fileEntry.currentContentId(),
                fileEntry.presenceStatus(),
                fileEntry.sizeBytes(),
                fileEntry.modifiedTimeEpochSecond(),
                fileEntry.modifiedTimeNano(),
                fileEntry.observationRevision(),
                fileEntry.lastSeenAtMs(),
                fileEntry.lastSeenScanRunSourceId(),
                fileEntry.lastSeenTraversalGeneration(),
                fileEntry.id());
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
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms"));
    }

    private static FileEntry mapFileEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FileEntry(
                resultSet.getLong("id"),
                resultSet.getLong("source_id"),
                resultSet.getString("relative_path"),
                resultSet.getString("path_key"),
                nullableLong(resultSet, "current_content_id"),
                resultSet.getString("presence_status"),
                resultSet.getLong("size_bytes"),
                nullableLong(resultSet, "modified_time_epoch_second"),
                nullableInteger(resultSet, "modified_time_nano"),
                resultSet.getLong("observation_revision"),
                resultSet.getLong("first_seen_at_ms"),
                resultSet.getLong("last_seen_at_ms"),
                nullableLong(resultSet, "last_seen_scan_run_source_id"),
                nullableLong(resultSet, "last_seen_traversal_generation"));
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
