package io.github.topher6835.mediacompare.scan;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ScanRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ScanRun insert(ScanRun scanRun) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scan_run (
                        request_key, request_type, status, working_set_id, options_version, options_json,
                        created_at_ms, started_at_ms, finished_at_ms, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, scanRun.requestKey());
            statement.setString(2, scanRun.requestType());
            statement.setString(3, scanRun.status());
            setNullableLong(statement, 4, scanRun.workingSetId());
            statement.setLong(5, scanRun.optionsVersion());
            statement.setString(6, scanRun.optionsJson());
            statement.setLong(7, scanRun.createdAtMs());
            setNullableLong(statement, 8, scanRun.startedAtMs());
            setNullableLong(statement, 9, scanRun.finishedAtMs());
            statement.setString(10, scanRun.errorMessage());
            return statement;
        }, keyHolder);

        return new ScanRun(generatedId(keyHolder), scanRun.requestKey(), scanRun.requestType(), scanRun.status(),
                scanRun.workingSetId(), scanRun.optionsVersion(), scanRun.optionsJson(), scanRun.createdAtMs(),
                scanRun.startedAtMs(), scanRun.finishedAtMs(), scanRun.errorMessage());
    }

    public Optional<ScanRun> findScanRunById(long id) {
        return jdbcTemplate.query("SELECT * FROM scan_run WHERE id = ?", (resultSet, rowNumber) -> new ScanRun(
                resultSet.getLong("id"),
                resultSet.getString("request_key"),
                resultSet.getString("request_type"),
                resultSet.getString("status"),
                nullableLong(resultSet, "working_set_id"),
                resultSet.getLong("options_version"),
                resultSet.getString("options_json"),
                resultSet.getLong("created_at_ms"),
                nullableLong(resultSet, "started_at_ms"),
                nullableLong(resultSet, "finished_at_ms"),
                resultSet.getString("error_message")), id).stream().findFirst();
    }

    public Optional<Long> findScanRunIdByRequestKey(String requestKey) {
        return jdbcTemplate.queryForList("SELECT id FROM scan_run WHERE request_key = ?", Long.class, requestKey)
                .stream().findFirst();
    }

    /** Reserve SQLite's writer before acceptance/ownership reads, avoiding deferred read-to-write races.
     * No row is changed. Must be called inside the short creation transaction, never from polling. */
    public void reserveExecutionWrite() {
        jdbcTemplate.update("UPDATE scan_run SET id = id WHERE 0");
    }

    public ScanRunSource insert(ScanRunSource scanRunSource) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scan_run_source (
                        scan_run_id, source_id, status, source_location_revision, traversal_generation,
                        completed_generation, started_at_ms, completed_at_ms, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, scanRunSource.scanRunId());
            statement.setLong(2, scanRunSource.sourceId());
            statement.setString(3, scanRunSource.status());
            statement.setLong(4, scanRunSource.sourceLocationRevision());
            statement.setLong(5, scanRunSource.traversalGeneration());
            setNullableLong(statement, 6, scanRunSource.completedGeneration());
            setNullableLong(statement, 7, scanRunSource.startedAtMs());
            setNullableLong(statement, 8, scanRunSource.completedAtMs());
            statement.setString(9, scanRunSource.errorMessage());
            return statement;
        }, keyHolder);

        return new ScanRunSource(generatedId(keyHolder), scanRunSource.scanRunId(), scanRunSource.sourceId(),
                scanRunSource.status(), scanRunSource.sourceLocationRevision(), scanRunSource.traversalGeneration(),
                scanRunSource.completedGeneration(), scanRunSource.startedAtMs(), scanRunSource.completedAtMs(),
                scanRunSource.errorMessage());
    }

    public Optional<ScanRunSource> findScanRunSourceById(long id) {
        return jdbcTemplate.query("SELECT * FROM scan_run_source WHERE id = ?", ScanRepository::mapScanRunSource, id)
                .stream()
                .findFirst();
    }

    public List<ScanRunSource> findScanRunSourcesByScanRunId(long scanRunId) {
        return jdbcTemplate.query("""
                SELECT * FROM scan_run_source
                WHERE scan_run_id = ?
                ORDER BY source_id
                """, ScanRepository::mapScanRunSource, scanRunId);
    }

    public int startScanRun(long scanRunId, long startedAtMs) {
        return jdbcTemplate.update("""
                UPDATE scan_run
                SET status = 'RUNNING', started_at_ms = ?, finished_at_ms = NULL, error_message = NULL
                WHERE id = ? AND status = 'PENDING'
                """, startedAtMs, scanRunId);
    }

    public int startSourceDiscovery(long scanRunSourceId, long traversalGeneration, long startedAtMs) {
        return jdbcTemplate.update("""
                UPDATE scan_run_source
                SET status = 'DISCOVERING', traversal_generation = ?, started_at_ms = ?,
                    completed_at_ms = NULL, error_message = NULL
                WHERE id = ? AND status = 'PENDING'
                """, traversalGeneration, startedAtMs, scanRunSourceId);
    }

    public int completeSourceDiscovery(long scanRunSourceId) {
        return jdbcTemplate.update("""
                UPDATE scan_run_source
                SET status = 'DISCOVERED', completed_at_ms = NULL, error_message = NULL
                WHERE id = ? AND status = 'DISCOVERING'
                """, scanRunSourceId);
    }

    public int failSourceDiscovery(long scanRunSourceId, long failedAtMs, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE scan_run_source
                SET status = 'FAILED', completed_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'DISCOVERING'
                """, failedAtMs, errorMessage, scanRunSourceId);
    }

    public int failScanRun(long scanRunId, long failedAtMs, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE scan_run
                SET status = 'FAILED', finished_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'RUNNING'
                """, failedAtMs, errorMessage, scanRunId);
    }

    public int completeSourceReconciliation(long scanRunSourceId, long traversalGeneration,
            long completedAtMs) {
        return jdbcTemplate.update("""
                UPDATE scan_run_source
                SET status = 'COMPLETED', completed_generation = traversal_generation,
                    completed_at_ms = ?, error_message = NULL
                WHERE id = ? AND status = 'DISCOVERED'
                  AND traversal_generation = ? AND traversal_generation > 0
                  AND completed_generation IS NULL AND completed_at_ms IS NULL
                """, completedAtMs, scanRunSourceId, traversalGeneration);
    }

    public int completeScanRun(long scanRunId, long completedAtMs) {
        return jdbcTemplate.update("""
                UPDATE scan_run
                SET status = 'COMPLETED', finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND status = 'RUNNING'
                """, completedAtMs, scanRunId);
    }

    public int failNonterminalScanRun(long scanRunId, long failedAtMs, String message) {
        return jdbcTemplate.update("""
                UPDATE scan_run SET status = 'FAILED', finished_at_ms = ?, error_message = ?
                WHERE id = ? AND status IN ('PENDING', 'RUNNING')
                """, failedAtMs, message, scanRunId);
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        return Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
    }

    private static ScanRunSource mapScanRunSource(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ScanRunSource(
                resultSet.getLong("id"),
                resultSet.getLong("scan_run_id"),
                resultSet.getLong("source_id"),
                resultSet.getString("status"),
                resultSet.getLong("source_location_revision"),
                resultSet.getLong("traversal_generation"),
                nullableLong(resultSet, "completed_generation"),
                nullableLong(resultSet, "started_at_ms"),
                nullableLong(resultSet, "completed_at_ms"),
                resultSet.getString("error_message"));
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }
}
