package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceBindingPeriodMigrationTests {
    @TempDir Path directory;

    private static final String CONTEXT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final List<String> PRESERVED_TABLES = List.of("source", "location_context",
            "file_entry", "source_membership", "content_record", "analysis_record");

    @Test
    void backfillsOnlyTheExactCurrentBoundPeriodAndPreservesCatalogRows() throws Exception {
        String url = url("backfill.db");
        migrate(url, "6");
        List<List<String>> before = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url)) {
            seed(connection);
            for (String table : PRESERVED_TABLES) {
                before.add(rows(connection, "SELECT * FROM " + table + " ORDER BY id"));
            }
        }

        migrate(url, null);
        try (Connection connection = DriverManager.getConnection(url)) {
            for (int index = 0; index < PRESERVED_TABLES.size(); index++) {
                assertEquals(before.get(index), rows(connection,
                        "SELECT * FROM " + PRESERVED_TABLES.get(index) + " ORDER BY id"));
            }
            assertEquals(List.of("10|4|" + CONTEXT_ID
                    + "|unix|/Volumes/Archive|lk1:exact|{\"original\":true}|25|null|null"), rows(connection, """
                    SELECT source_id, bound_source_location_revision, location_context_id,
                           root_path_dialect, root_path, root_path_key, binding_evidence_json,
                           bound_at_ms, unbound_source_location_revision, unbound_at_ms
                    FROM source_binding_period ORDER BY source_id
                    """));
            assertEquals(0, count(connection,
                    "SELECT COUNT(*) FROM source_binding_period WHERE source_id = 11"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void partialBindingShapeFailsMigrationWithoutChangingSource() throws Exception {
        for (String badColumn : List.of("root_path_dialect", "bound_location_context_id",
                "binding_evidence_json")) {
            String url = url("partial-" + badColumn + ".db");
            migrate(url, "6");
            List<String> before;
            try (Connection connection = DriverManager.getConnection(url)) {
                seed(connection);
                connection.createStatement().executeUpdate(
                        "UPDATE source SET " + badColumn + " = NULL WHERE id = 10");
                before = rows(connection, "SELECT * FROM source ORDER BY id");
            }
            assertThrows(RuntimeException.class, () -> migrate(url, null));
            try (Connection connection = DriverManager.getConnection(url)) {
                assertEquals(before, rows(connection, "SELECT * FROM source ORDER BY id"));
                assertEquals(0, count(connection, """
                        SELECT COUNT(*) FROM sqlite_schema
                        WHERE type = 'table' AND name = 'source_binding_period'
                        """));
            }
        }
    }

    @Test
    void constraintsRejectDuplicateOpenPeriodsAndInvalidClosingFields() throws Exception {
        String url = url("constraints.db");
        migrate(url, "6");
        try (Connection connection = DriverManager.getConnection(url)) {
            seed(connection);
        }
        migrate(url, null);
        try (Connection connection = DriverManager.getConnection(url)) {
            String insert = """
                    INSERT INTO source_binding_period (
                        source_id, bound_source_location_revision, location_context_id,
                        root_path_dialect, root_path, root_path_key, binding_evidence_json,
                        bound_at_ms, unbound_source_location_revision, unbound_at_ms
                    ) VALUES (10, ?, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
                              'unix', '/Volumes/Archive', 'lk1:exact', '{}', 25, ?, ?)
                    """;
            assertInvalid(connection, insert, 5, null, null); // another open period
            assertInvalid(connection, insert, 4, 6, 30); // duplicate bound revision
            assertInvalid(connection, insert, 5, 6, null);
            assertInvalid(connection, insert, 5, null, 30);
            assertInvalid(connection, insert, 5, 5, 30);
            assertInvalid(connection, insert, 5, 6, 24);
            assertInvalid(connection, insert, -1, 6, 30);
            assertEquals(List.of("source|source_id|id|RESTRICT",
                    "location_context|location_context_id|id|RESTRICT"), rows(connection, """
                    SELECT "table", "from", "to", on_delete
                    FROM pragma_foreign_key_list('source_binding_period')
                    ORDER BY "table" DESC
                    """));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "DELETE FROM source WHERE id = 10"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "DELETE FROM location_context WHERE id = '" + CONTEXT_ID + "'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM source_binding_period"));

            connection.createStatement().executeUpdate("""
                    UPDATE source_binding_period
                    SET unbound_source_location_revision = 5, unbound_at_ms = 26
                    WHERE source_id = 10
                    """);
            try (var statement = connection.prepareStatement(insert)) {
                statement.setLong(1, 6);
                statement.setNull(2, java.sql.Types.INTEGER);
                statement.setNull(3, java.sql.Types.INTEGER);
                assertEquals(1, statement.executeUpdate());
            }
            assertEquals(1, count(connection, """
                    SELECT COUNT(*) FROM source_binding_period
                    WHERE source_id = 10 AND unbound_at_ms IS NULL
                    """));
        }
    }

    private static void assertInvalid(Connection connection, String sql, long boundRevision,
            Integer closingRevision, Integer closingTime) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, boundRevision);
            if (closingRevision == null) statement.setNull(2, java.sql.Types.INTEGER);
            else statement.setInt(2, closingRevision);
            if (closingTime == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, closingTime);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private static void seed(Connection connection) throws SQLException {
        connection.createStatement().executeUpdate("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'anchor', 'key',
                    'ACTIVE', 'ACCEPTED', 3, '{"context":true}', 1, 20)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    root_path_dialect, bound_location_context_id, binding_evidence_json,
                    created_at_ms, updated_at_ms) VALUES
                    (10, 'Bound', '/Volumes/Archive', 'lk1:exact', 4, 'unix',
                     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '{"original":true}', 2, 25),
                    (11, 'Unbound', '/other', 'legacy', 2, NULL, NULL, NULL, 3, 26)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (30, 42, 4)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO file_entry (id, location_identity_status, current_content_id,
                    size_bytes, observation_revision, first_seen_at_ms, last_seen_at_ms)
                VALUES (40, 'UNRESOLVED', 30, 42, 2, 5, 6)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, membership_revision,
                    observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms)
                VALUES (50, 10, 40, 'photo.jpg', 'photo.jpg', 'ACTIVE', 'PRESENT', 1, 2, 5, 6)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO analysis_record (id, content_record_id, analysis_type, analyzer_id,
                    analyzer_version, configuration_version, configuration_hash,
                    configuration_json, status, created_at_ms)
                VALUES (60, 30, 'CONTENT_HASH', 'builtin.sha256', '1', 1,
                    'hash', '{}', 'COMPLETED', 7)
                """);
    }

    private String url(String name) {
        return "jdbc:sqlite:" + directory.resolve(name) + "?foreign_keys=on";
    }

    private static void migrate(String url, String target) {
        var configuration = Flyway.configure().dataSource(url, null, null)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        configuration.load().migrate();
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var result = connection.createStatement().executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
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
