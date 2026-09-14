package io.github.topher6835.mediacompare.scan;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
                        request_type, status, working_set_id, options_version, options_json,
                        created_at_ms, started_at_ms, finished_at_ms, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, scanRun.requestType());
            statement.setString(2, scanRun.status());
            setNullableLong(statement, 3, scanRun.workingSetId());
            statement.setLong(4, scanRun.optionsVersion());
            statement.setString(5, scanRun.optionsJson());
            statement.setLong(6, scanRun.createdAtMs());
            setNullableLong(statement, 7, scanRun.startedAtMs());
            setNullableLong(statement, 8, scanRun.finishedAtMs());
            statement.setString(9, scanRun.errorMessage());
            return statement;
        }, keyHolder);

        return new ScanRun(generatedId(keyHolder), scanRun.requestType(), scanRun.status(), scanRun.workingSetId(),
                scanRun.optionsVersion(), scanRun.optionsJson(), scanRun.createdAtMs(), scanRun.startedAtMs(),
                scanRun.finishedAtMs(), scanRun.errorMessage());
    }

    public Optional<ScanRun> findScanRunById(long id) {
        return jdbcTemplate.query("SELECT * FROM scan_run WHERE id = ?", (resultSet, rowNumber) -> new ScanRun(
                resultSet.getLong("id"),
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
        return jdbcTemplate.query("SELECT * FROM scan_run_source WHERE id = ?",
                (resultSet, rowNumber) -> new ScanRunSource(
                        resultSet.getLong("id"),
                        resultSet.getLong("scan_run_id"),
                        resultSet.getLong("source_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("source_location_revision"),
                        resultSet.getLong("traversal_generation"),
                        nullableLong(resultSet, "completed_generation"),
                        nullableLong(resultSet, "started_at_ms"),
                        nullableLong(resultSet, "completed_at_ms"),
                        resultSet.getString("error_message")),
                id).stream().findFirst();
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        return Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
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
