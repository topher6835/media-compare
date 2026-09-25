package io.github.topher6835.mediacompare.scan;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndexingRunRepository {
    private static final String SUMMARY_COLUMNS = """
            j.scan_run_id, j.id AS job_id, j.status, j.current_stage_type,
            j.progress_completed, j.progress_total, r.created_at_ms,
            j.started_at_ms, j.finished_at_ms, j.error_message,
            a.status AS assignment_status, a.result_json AS assignment_json,
            h.status AS hashing_status, h.result_json AS hashing_json
            """;
    private static final String RESULT_JOINS = """
            LEFT JOIN job_stage a ON a.job_id = j.id AND a.stage_type = 'CONTENT_ASSIGNMENT'
            LEFT JOIN job_stage h ON h.job_id = j.id AND h.stage_type = 'CONTENT_HASHING'
            """;
    private final JdbcTemplate jdbc;

    public IndexingRunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SummaryRow> findActive() {
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + """
                FROM job j JOIN scan_run r ON r.id = j.scan_run_id
                """ + RESULT_JOINS + """
                WHERE j.job_type = 'SCAN' AND j.execution_version IN (2, 3)
                  AND j.status IN ('PENDING', 'RUNNING') AND r.request_type = 'INDEX'
                """, (rs, row) -> summary(rs)).stream().findFirst();
    }

    public List<SourceRow> findLatestBySource() {
        return jdbc.query("""
                WITH ranked AS (
                    SELECT srs.source_id, j.id AS job_id,
                        ROW_NUMBER() OVER (PARTITION BY srs.source_id
                            ORDER BY r.created_at_ms DESC, r.id DESC) AS rank
                    FROM scan_run_source srs
                    JOIN scan_run r ON r.id = srs.scan_run_id
                    JOIN job j ON j.scan_run_id = r.id
                    WHERE j.job_type = 'SCAN' AND j.execution_version IN (2, 3)
                      AND r.request_type = 'INDEX'
                )
                SELECT s.id AS source_id,
                """ + SUMMARY_COLUMNS + """
                FROM source s
                LEFT JOIN ranked latest ON latest.source_id = s.id AND latest.rank = 1
                LEFT JOIN job j ON j.id = latest.job_id
                LEFT JOIN scan_run r ON r.id = j.scan_run_id
                """ + RESULT_JOINS + " ORDER BY s.id",
                (rs, row) -> new SourceRow(rs.getLong("source_id"),
                        rs.getObject("job_id") == null ? null : summary(rs)));
    }

    private static SummaryRow summary(ResultSet rs) throws SQLException {
        return new SummaryRow(rs.getLong("scan_run_id"), rs.getLong("job_id"), rs.getString("status"),
                rs.getString("current_stage_type"), rs.getLong("progress_completed"), nullableLong(rs, "progress_total"),
                rs.getLong("created_at_ms"), nullableLong(rs, "started_at_ms"), nullableLong(rs, "finished_at_ms"),
                rs.getString("error_message"), rs.getString("assignment_status"), rs.getString("assignment_json"),
                rs.getString("hashing_status"), rs.getString("hashing_json"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record SourceRow(long sourceId, SummaryRow latest) {}
    public record SummaryRow(long scanRunId, long jobId, String status, String currentStage,
            long progressCompleted, Long progressTotal, long createdAtMs, Long startedAtMs, Long finishedAtMs,
            String errorMessage, String assignmentStatus, String assignmentJson, String hashingStatus, String hashingJson) {}
}
