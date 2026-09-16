package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableIndexingMigrationTests {

    private static final Set<String> APPLICATION_TABLES = Set.of(
            "source",
            "content_record",
            "file_entry",
            "working_set",
            "working_set_content",
            "scan_run",
            "scan_run_source",
            "job",
            "job_stage",
            "analysis_record",
            "content_hash");

    @TempDir
    Path temporaryDirectory;

    @Test
    void v3UpgradesExistingRowsWithoutChangingRelationships() throws Exception {
        String databaseUrl = databaseUrl("upgrade.db");
        migrateToV2(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO source (
                        id, name, root_path, root_path_key, location_revision, created_at_ms, updated_at_ms
                    ) VALUES (10, 'Existing source', '/existing', '/existing', 0, 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO scan_run (
                        id, request_type, status, options_version, options_json, created_at_ms
                    ) VALUES (20, 'INDEX', 'RUNNING', 1, '{}', 2)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO scan_run_source (
                        id, scan_run_id, source_id, status, source_location_revision, traversal_generation
                    ) VALUES (30, 20, 10, 'DISCOVERED', 0, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO job (
                        id, scan_run_id, job_type, status, current_stage_type,
                        progress_completed, attempt_count, created_at_ms
                    ) VALUES (40, 20, 'SCAN', 'RUNNING', 'RECONCILIATION', 1, 1, 3)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO job_stage (
                        id, job_id, stage_type, status, progress_completed, attempt_count, created_at_ms
                    ) VALUES (50, 40, 'DISCOVERY', 'COMPLETED', 1, 1, 3)
                    """);
        }

        migrateLatest(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            Set<String> tables = Set.copyOf(queryStrings(connection, """
                    SELECT name
                    FROM sqlite_schema
                    WHERE type = 'table'
                      AND name NOT LIKE 'sqlite_%'
                      AND name <> 'flyway_schema_history'
                    """));
            assertEquals(APPLICATION_TABLES, tables);

            try (var rows = connection.createStatement().executeQuery("""
                    SELECT sr.request_key, j.execution_version, js.result_json,
                           srs.source_id, j.scan_run_id, js.job_id
                    FROM scan_run sr
                    JOIN scan_run_source srs ON srs.scan_run_id = sr.id
                    JOIN job j ON j.scan_run_id = sr.id
                    JOIN job_stage js ON js.job_id = j.id
                    WHERE sr.id = 20
                    """)) {
                assertTrue(rows.next());
                assertNull(rows.getString("request_key"));
                assertEquals(1, rows.getInt("execution_version"));
                assertNull(rows.getString("result_json"));
                assertEquals(10, rows.getLong("source_id"));
                assertEquals(20, rows.getLong("scan_run_id"));
                assertEquals(40, rows.getLong("job_id"));
                assertFalse(rows.next());
            }

            assertEquals(Set.of(
                    "uq_scan_run_request_key",
                    "uq_job_v2_scan_run",
                    "uq_job_active_v2_scan"), Set.copyOf(queryStrings(connection, """
                            SELECT name
                            FROM sqlite_schema
                            WHERE type = 'index' AND name LIKE 'uq_%'
                            """)));
        }
    }

    @Test
    void v3EnforcesRequestKeyAndFutureV2AdmissionConstraints() throws Exception {
        String databaseUrl = databaseUrl("constraints.db");
        migrateLatest(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            long firstNullKeyRun = insertScanRun(connection, null);
            long secondNullKeyRun = insertScanRun(connection, null);
            long firstKeyedRun = insertScanRun(connection, "request-one");
            long secondKeyedRun = insertScanRun(connection, "request-two");
            assertThrows(SQLException.class, () -> insertScanRun(connection, "request-one"));

            assertThrows(SQLException.class,
                    () -> insertJob(connection, firstNullKeyRun, "SCAN", 0, "COMPLETED"));

            insertJob(connection, firstNullKeyRun, "SCAN", 1, "PENDING");
            insertJob(connection, firstNullKeyRun, "SCAN", 1, "RUNNING");

            insertJob(connection, firstNullKeyRun, "SCAN", 2, "COMPLETED");
            assertThrows(SQLException.class,
                    () -> insertJob(connection, firstNullKeyRun, "SCAN", 2, "FAILED"));

            insertJob(connection, secondNullKeyRun, "SCAN", 1, "PENDING");
            insertJob(connection, secondKeyedRun, "SCAN", 1, "RUNNING");
            insertJob(connection, secondNullKeyRun, "EXPORT", 2, "PENDING");
            insertJob(connection, secondKeyedRun, "EXPORT", 2, "RUNNING");

            long activePendingJob = insertJob(connection, firstKeyedRun, "SCAN", 2, "PENDING");
            assertThrows(SQLException.class,
                    () -> insertJob(connection, secondNullKeyRun, "SCAN", 2, "PENDING"));
            assertThrows(SQLException.class,
                    () -> insertJob(connection, secondNullKeyRun, "SCAN", 2, "RUNNING"));

            updateStatus(connection, activePendingJob, "COMPLETED");
            long activeRunningJob = insertJob(connection, secondNullKeyRun, "SCAN", 2, "RUNNING");
            assertThrows(SQLException.class,
                    () -> insertJob(connection, secondKeyedRun, "SCAN", 2, "PENDING"));

            updateStatus(connection, activeRunningJob, "FAILED");
            long nextPendingJob = insertJob(connection, secondKeyedRun, "SCAN", 2, "PENDING");
            updateStatus(connection, nextPendingJob, "COMPLETED");

            assertEquals(4, queryCount(connection, """
                    SELECT COUNT(*) FROM job
                    WHERE job_type = 'SCAN' AND execution_version = 2
                      AND status IN ('COMPLETED', 'FAILED')
                    """));
        }
    }

    private String databaseUrl(String filename) {
        return "jdbc:sqlite:" + temporaryDirectory.resolve(filename) + "?foreign_keys=on";
    }

    private void migrateToV2(String databaseUrl) {
        Flyway.configure()
                .dataSource(databaseUrl, null, null)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();
    }

    private void migrateLatest(String databaseUrl) {
        Flyway.configure()
                .dataSource(databaseUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private long insertScanRun(Connection connection, String requestKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO scan_run (
                    request_key, request_type, status, options_version, options_json, created_at_ms
                ) VALUES (?, 'INDEX', 'PENDING', 1, '{}', 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, requestKey);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private long insertJob(Connection connection, long scanRunId, String jobType, int executionVersion,
            String status) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO job (
                    scan_run_id, job_type, execution_version, status,
                    progress_completed, attempt_count, created_at_ms
                ) VALUES (?, ?, ?, ?, 0, 0, 1)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, scanRunId);
            statement.setString(2, jobType);
            statement.setInt(3, executionVersion);
            statement.setString(4, status);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void updateStatus(Connection connection, long jobId, String status) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE job SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setLong(2, jobId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private int queryCount(Connection connection, String sql) throws SQLException {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private List<String> queryStrings(Connection connection, String sql) throws SQLException {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }
}
