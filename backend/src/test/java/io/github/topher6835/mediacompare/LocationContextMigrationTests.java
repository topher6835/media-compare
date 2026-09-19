package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocationContextMigrationTests {

    private static final Set<String> V5_APPLICATION_TABLES = Set.of(
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
            "content_hash",
            "location_context");

    @TempDir
    Path temporaryDirectory;

    @Test
    void v5UpgradesRepresentativeV4DataWithoutBackfillOrRelationshipChanges() throws Exception {
        String databaseUrl = databaseUrl("v5-upgrade.db");
        migrateToV4(databaseUrl);
        List<String> sourceRootBytesBefore;

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO source (
                        id, name, root_path, root_path_key, location_revision, created_at_ms, updated_at_ms
                    ) VALUES
                        (10, 'First', '/Volumes/Media', '/Volumes/Media', 0, 100, 101),
                        (11, 'Second', 'D:\\Archive', 'D:\\Archive', 7, 200, 250)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO content_record (id, size_bytes, created_at_ms)
                    VALUES (100, 42, 300), (101, 84, 301)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO scan_run (
                        id, request_key, request_type, status, options_version, options_json,
                        created_at_ms, started_at_ms, finished_at_ms
                    ) VALUES (20, 'request-key', 'INDEX', 'COMPLETED', 1, '{}', 400, 401, 450)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO scan_run_source (
                        id, scan_run_id, source_id, status, source_location_revision,
                        traversal_generation, completed_generation, started_at_ms, completed_at_ms
                    ) VALUES
                        (30, 20, 10, 'COMPLETED', 0, 2, 2, 410, 420),
                        (31, 20, 11, 'COMPLETED', 7, 3, 3, 411, 421)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO file_entry (
                        id, source_id, relative_path, path_key, extension_key, current_content_id,
                        presence_status, size_bytes, modified_time_epoch_second, modified_time_nano,
                        observation_revision, first_seen_at_ms, last_seen_at_ms,
                        last_seen_scan_run_source_id, last_seen_traversal_generation
                    ) VALUES
                        (40, 10, 'Photo.JPG', 'Photo.JPG', 'jpg', 100, 'PRESENT', 42, 500, 12,
                            4, 501, 502, 30, 2),
                        (41, 11, 'Missing.mov', 'Missing.mov', 'mov', 101, 'MISSING', 84, 600, 34,
                            5, 601, 602, 31, 3)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO job (
                        id, scan_run_id, job_type, execution_version, status, current_stage_type,
                        progress_completed, progress_total, attempt_count,
                        created_at_ms, started_at_ms, finished_at_ms
                    ) VALUES (50, 20, 'SCAN', 2, 'COMPLETED', 'CONTENT_HASHING',
                        2, 2, 1, 700, 701, 750)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO job_stage (
                        id, job_id, stage_type, result_json, status, progress_completed,
                        progress_total, attempt_count, created_at_ms, started_at_ms, finished_at_ms
                    ) VALUES (51, 50, 'CONTENT_HASHING', '{"resultVersion":1}', 'COMPLETED',
                        2, 2, 1, 710, 711, 749)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO analysis_record (
                        id, content_record_id, analysis_type, analyzer_id, analyzer_version,
                        configuration_version, configuration_hash, configuration_json, result_json,
                        status, attempt_count, created_at_ms, started_at_ms, finished_at_ms
                    ) VALUES (60, 100, 'CONTENT_HASH', 'builtin.sha256', '1', 1,
                        'hash', '{}', NULL, 'COMPLETED', 1, 800, 801, 802)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex)
                    VALUES (60, 'SHA-256', 'abc123')
                    """);
            sourceRootBytesBefore = queryRows(connection, """
                    SELECT id, hex(CAST(root_path AS BLOB)), hex(CAST(root_path_key AS BLOB))
                    FROM source ORDER BY id
                    """);
        }

        migrateLatest(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            assertEquals(List.of(
                    "10|First|/Volumes/Media|/Volumes/Media|0|100|101",
                    "11|Second|D:\\Archive|D:\\Archive|7|200|250"), queryRows(connection, """
                            SELECT id, name, root_path, root_path_key, location_revision,
                                   created_at_ms, updated_at_ms
                            FROM source ORDER BY id
                            """));
            assertEquals(List.of("10|null|null|null", "11|null|null|null"), queryRows(connection, """
                    SELECT id, root_path_dialect, bound_location_context_id, binding_evidence_json
                    FROM source ORDER BY id
                    """));
            assertEquals(sourceRootBytesBefore, queryRows(connection, """
                    SELECT id, hex(CAST(root_path AS BLOB)), hex(CAST(root_path_key AS BLOB))
                    FROM source ORDER BY id
                    """));
            assertEquals(0, queryCount(connection, "SELECT COUNT(*) FROM location_context"));
            assertEquals(List.of(
                    "40|10|Photo.JPG|Photo.JPG|jpg|100|PRESENT|42|500|12|4|501|502|30|2",
                    "41|11|Missing.mov|Missing.mov|mov|101|MISSING|84|600|34|5|601|602|31|3"),
                    queryRows(connection, """
                            SELECT id, source_id, relative_path, path_key, extension_key,
                                   current_content_id, presence_status, size_bytes,
                                   modified_time_epoch_second, modified_time_nano,
                                   observation_revision, first_seen_at_ms, last_seen_at_ms,
                                   last_seen_scan_run_source_id, last_seen_traversal_generation
                            FROM file_entry ORDER BY id
                            """));
            assertEquals(List.of("100|42|300", "101|84|301"),
                    queryRows(connection, "SELECT * FROM content_record ORDER BY id"));
            assertEquals(List.of("20|INDEX|COMPLETED|null|1|{}|400|401|450|null|request-key"),
                    queryRows(connection, "SELECT * FROM scan_run ORDER BY id"));
            assertEquals(List.of(
                    "30|20|10|COMPLETED|0|2|2|410|420|null",
                    "31|20|11|COMPLETED|7|3|3|411|421|null"),
                    queryRows(connection, "SELECT * FROM scan_run_source ORDER BY id"));
            assertEquals(List.of("60|100|CONTENT_HASH|builtin.sha256|1|1|hash|{}|COMPLETED|1|800|801|802|null|null"),
                    queryRows(connection, "SELECT * FROM analysis_record ORDER BY id"));
            assertEquals(List.of("60|SHA-256|abc123"),
                    queryRows(connection, "SELECT * FROM content_hash ORDER BY analysis_record_id"));
            assertEquals(0, queryCount(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void freshV5SchemaHasOnlyTheLocationContextFoundation() throws Exception {
        String databaseUrl = databaseUrl("v5-fresh.db");
        migrateLatest(databaseUrl);

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            assertEquals(V5_APPLICATION_TABLES, Set.copyOf(queryStrings(connection, """
                    SELECT name
                    FROM sqlite_schema
                    WHERE type = 'table'
                      AND name NOT LIKE 'sqlite_%'
                      AND name <> 'flyway_schema_history'
                    """)));
            assertFalse(V5_APPLICATION_TABLES.contains("source_membership"));
            assertEquals(Set.of(
                    "root_path_dialect",
                    "bound_location_context_id",
                    "binding_evidence_json"), Set.copyOf(queryStrings(connection, """
                            SELECT name
                            FROM pragma_table_info('source')
                            WHERE name IN (
                                'root_path_dialect',
                                'bound_location_context_id',
                                'binding_evidence_json'
                            )
                            """)));
            assertEquals(Set.of(
                    "uq_location_context_active_anchor",
                    "idx_source_bound_location_context"), Set.copyOf(queryStrings(connection, """
                            SELECT name
                            FROM sqlite_schema
                            WHERE type = 'index'
                              AND name IN (
                                  'uq_location_context_active_anchor',
                                  'idx_source_bound_location_context'
                              )
                            """)));
            assertEquals(List.of("location_context|bound_location_context_id|id|RESTRICT"),
                    queryRows(connection, """
                            SELECT "table", "from", "to", on_delete
                            FROM pragma_foreign_key_list('source')
                            WHERE "from" = 'bound_location_context_id'
                            """));
        }
    }

    private String databaseUrl(String filename) {
        return "jdbc:sqlite:" + temporaryDirectory.resolve(filename) + "?foreign_keys=on";
    }

    private void migrateToV4(String databaseUrl) {
        Flyway.configure()
                .dataSource(databaseUrl, null, null)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("4"))
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

    private int queryCount(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private List<String> queryStrings(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private List<String> queryRows(Connection connection, String sql) throws Exception {
        try (var rows = connection.createStatement().executeQuery(sql)) {
            var values = new ArrayList<String>();
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                var row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    if (column > 1) {
                        row.append('|');
                    }
                    String value = rows.getString(column);
                    row.append(value == null ? "null" : value);
                }
                values.add(row.toString());
            }
            return values;
        }
    }
}
