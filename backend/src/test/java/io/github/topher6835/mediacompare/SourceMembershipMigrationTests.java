package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceMembershipMigrationTests {
    @TempDir Path directory;

    @Test
    void preservesV5RowsAsDistinctUnresolvedEntriesWithOneMembershipEach() throws Exception {
        String url = url("preserve.db");
        migrate(url, "5");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                        lifecycle_status, continuity_status, revision, continuity_evidence_json,
                        created_at_ms, updated_at_ms)
                    VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'anchor', 'anchor',
                        'ACTIVE', 'ACCEPTED', 2, '{}', 1, 2)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                        root_path_dialect, bound_location_context_id, binding_evidence_json,
                        created_at_ms, updated_at_ms) VALUES
                        (10, 'Bound', '/Volumes/Archive', 'root-a', 3, 'unix',
                         'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '{}', 1, 2),
                        (11, 'Unbound', '/Volumes/Archive/Photos', 'root-b', 0,
                         NULL, NULL, NULL, 1, 2)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO content_record (id, size_bytes, created_at_ms)
                    VALUES (100, 42, 1), (101, 42, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO scan_run (id, request_type, status, options_version,
                        options_json, created_at_ms)
                    VALUES (20, 'INDEX', 'COMPLETED', 1, '{}', 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                        source_location_revision, traversal_generation, completed_generation)
                    VALUES (30, 20, 10, 'COMPLETED', 3, 2, 2)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO file_entry (id, source_id, relative_path, path_key, extension_key,
                        current_content_id, presence_status, size_bytes,
                        modified_time_epoch_second, modified_time_nano, observation_revision,
                        first_seen_at_ms, last_seen_at_ms, last_seen_scan_run_source_id,
                        last_seen_traversal_generation) VALUES
                        (40, 10, 'Photos/a.JPG', 'Photos/a.JPG', 'jpg', 100,
                         'PRESENT', 42, 500, 123, 4, 1000, 1001, 30, 2),
                        (41, 11, 'a.JPG', 'a.JPG', 'jpg', 101,
                         'MISSING', 42, 500, 123, 5, 1002, 1003, NULL, NULL),
                        (42, 11, 'A.jpg', 'A.jpg', 'jpg', NULL,
                         'PRESENT', 0, NULL, NULL, 0, 1004, 1005, NULL, NULL)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO analysis_record (id, content_record_id, analysis_type, analyzer_id,
                        analyzer_version, configuration_version, configuration_hash,
                        configuration_json, status, created_at_ms)
                    VALUES (60, 100, 'CONTENT_HASH', 'builtin.sha256', '1', 1, 'hash', '{}',
                        'COMPLETED', 1),
                        (61, 101, 'CONTENT_HASH', 'builtin.sha256', '1', 1, 'hash', '{}',
                        'COMPLETED', 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex)
                    VALUES (60, 'SHA-256', 'same'), (61, 'SHA-256', 'same')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO working_set (id, name, created_at_ms, updated_at_ms)
                    VALUES (70, 'Saved', 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO working_set_content (working_set_id, content_record_id, added_at_ms)
                    VALUES (70, 100, 1)
                    """);
        }

        migrate(url, null);
        try (Connection connection = DriverManager.getConnection(url)) {
            assertEquals(List.of(
                    "40|UNRESOLVED|null|null|null|100|42|500|123|jpg|4|1000|1001",
                    "41|UNRESOLVED|null|null|null|101|42|500|123|jpg|5|1002|1003",
                    "42|UNRESOLVED|null|null|null|null|0|null|null|jpg|0|1004|1005"),
                    rows(connection, "SELECT * FROM file_entry ORDER BY id"));
            assertEquals(List.of(
                    "10|40|Photos/a.JPG|Photos/a.JPG|ACTIVE|PRESENT|0|4|1000|1001|30|2|null|null",
                    "11|41|a.JPG|a.JPG|ACTIVE|MISSING|0|5|1002|1003|null|null|null|null",
                    "11|42|A.jpg|A.jpg|ACTIVE|PRESENT|0|0|1004|1005|null|null|null|null"),
                    rows(connection, """
                            SELECT source_id, file_entry_id, relative_path, path_key,
                                   applicability_status, presence_status, membership_revision,
                                   observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                                   last_positive_scan_run_source_id,
                                   last_positive_traversal_generation,
                                   observed_source_location_revision,
                                   observed_location_context_revision
                            FROM source_membership ORDER BY file_entry_id
                            """));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM content_record"));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM analysis_record"));
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM content_hash"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM working_set_content"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void invalidV5PresenceRollsBackTheWholeRebuild() throws Exception {
        String url = url("rollback.db");
        migrate(url, "5");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO source (id, name, root_path, root_path_key, created_at_ms, updated_at_ms)
                    VALUES (1, 'Legacy', '/root', '/root', 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO file_entry (id, source_id, relative_path, path_key, presence_status,
                        size_bytes, first_seen_at_ms, last_seen_at_ms)
                    VALUES (2, 1, 'bad', 'bad', 'UNKNOWN', 0, 1, 1)
                    """);
        }
        assertThrows(RuntimeException.class, () -> migrate(url, null));
        try (Connection connection = DriverManager.getConnection(url)) {
            assertEquals(0, count(connection, """
                    SELECT COUNT(*) FROM sqlite_schema WHERE type = 'table'
                        AND name = 'source_membership'
                    """));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM file_entry WHERE id = 2"));
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM pragma_table_info('file_entry') WHERE name = 'source_id'
                    """));
        }
    }

    private String url(String name) {
        return "jdbc:sqlite:" + directory.resolve(name) + "?foreign_keys=on";
    }

    private static void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, null, null)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (var result = connection.createStatement().executeQuery(sql)) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static List<String> rows(Connection connection, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (var result = connection.createStatement().executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    if (column > 1) row.append('|');
                    row.append(result.getString(column));
                }
                values.add(row.toString());
            }
        }
        return values;
    }
}
