package io.github.topher6835.mediacompare.catalog;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        return jdbcTemplate.query("SELECT * FROM source WHERE id = ?", (resultSet, rowNumber) -> new Source(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("root_path"),
                resultSet.getString("root_path_key"),
                resultSet.getLong("location_revision"),
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms")), id).stream().findFirst();
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

    @Transactional
    public FileEntry insert(FileEntry fileEntry) {
        validateLastSeenSource(fileEntry);

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

        Integer matchingRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM scan_run_source
                WHERE id = ? AND source_id = ?
                """, Integer.class, fileEntry.lastSeenScanRunSourceId(), fileEntry.sourceId());

        if (matchingRows == null || matchingRows != 1) {
            throw new DataIntegrityViolationException(
                    "FileEntry Source " + fileEntry.sourceId()
                            + " does not match ScanRunSource " + fileEntry.lastSeenScanRunSourceId());
        }
    }

    public Optional<FileEntry> findFileEntryById(long id) {
        return jdbcTemplate.query("SELECT * FROM file_entry WHERE id = ?", (resultSet, rowNumber) -> new FileEntry(
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
                nullableLong(resultSet, "last_seen_traversal_generation")), id).stream().findFirst();
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
