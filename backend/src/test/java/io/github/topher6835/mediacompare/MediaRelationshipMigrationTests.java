package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaRelationshipMigrationTests {
    @TempDir Path directory;

    @Test
    void upgradesV7WithoutChangingExistingCatalogRowsOrProjectingHashes() throws Exception {
        String url = url("preserve.db");
        migrate(url, "7");
        Map<String, List<String>> before;
        try (Connection connection = DriverManager.getConnection(url)) {
            seedCatalog(connection);
            before = catalogSnapshot(connection);
        }

        migrate(url, "8");
        try (Connection connection = DriverManager.getConnection(url)) {
            Map<String, List<String>> after = catalogSnapshot(connection);
            assertEquals(14, before.size());
            assertEquals(List.of(), after.remove("media_relationship"));
            assertEquals(before, after);
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void endpointsAreCanonicalDistinctAndBothForeignKeysRestrictDeletion() throws Exception {
        String url = freshDatabase("endpoints.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            insert(connection, "test.matcher", "1", 1, "hash");
            for (String assignment : List.of("content_record_b_id = 1",
                    "content_record_a_id = 2, content_record_b_id = 1",
                    "content_record_a_id = 999", "content_record_b_id = 999")) {
                assertInvalidUpdate(connection, assignment);
            }
            assertEquals(List.of("content_record|content_record_a_id|id|RESTRICT",
                    "content_record|content_record_b_id|id|RESTRICT"), rows(connection, """
                    SELECT "table", "from", "to", on_delete
                    FROM pragma_foreign_key_list('media_relationship') ORDER BY "from"
                    """));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "DELETE FROM content_record WHERE id = 1"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                    "DELETE FROM content_record WHERE id = 2"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void validatesConfidenceJsonAndDirectionWithoutLockingRelationshipTypes() throws Exception {
        String url = freshDatabase("values.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            insert(connection, "test.matcher", "1", 1, "hash");
            for (String assignment : List.of("confidence = -0.1", "confidence = 1.1",
                    "confidence = 1e999", "confidence = -1e999", "confidence = 'invalid'",
                    "direction = 'UNKNOWN'", "relationship_type = ''", "configuration_version = 0",
                    "matcher_id = ''", "matcher_version = ''", "configuration_hash = ''")) {
                assertInvalidUpdate(connection, assignment);
            }
            for (String column : List.of("evidence_json", "configuration_json")) {
                for (String invalid : List.of("", " ", "not-json", "[]", "null", "{} {}",
                        "{\"x\":\"" + "é".repeat(65536) + "\"}")) {
                    try (var statement = connection.prepareStatement(
                            "UPDATE media_relationship SET " + column + " = ?")) {
                        statement.setString(1, invalid);
                        assertThrows(SQLException.class, statement::executeUpdate);
                    }
                }
            }
            for (String confidence : List.of("NULL", "0.0", "0.5", "1.0")) {
                connection.createStatement().executeUpdate("UPDATE media_relationship SET confidence = " + confidence);
            }
            // Future types can be added in Java without rebuilding the table; CROP can be undirected.
            connection.createStatement().executeUpdate("UPDATE media_relationship SET relationship_type = 'FUTURE_TYPE'");
            assertEquals(List.of("FUTURE_TYPE|UNDIRECTED"), rows(connection,
                    "SELECT relationship_type, direction FROM media_relationship"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void uniquenessUsesPairTypeAndExactMatcherConfigurationIdentity() throws Exception {
        String url = freshDatabase("identity.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            insert(connection, "test.matcher", "1", 1, "hash");
            assertThrows(SQLException.class, () -> insert(connection, "test.matcher", "1", 1, "hash"));
            insert(connection, "test.matcher", "2", 1, "hash");
            insert(connection, "test.matcher", "1", 2, "hash");
            insert(connection, "test.matcher", "1", 1, "new-hash");
            insert(connection, "other.matcher", "1", 1, "hash");
            connection.createStatement().executeUpdate("""
                    INSERT INTO media_relationship (
                        content_record_a_id, content_record_b_id, relationship_type, direction,
                        confidence, evidence_json, matcher_id, matcher_version,
                        configuration_version, configuration_hash, configuration_json, created_at_ms
                    ) VALUES (1, 2, 'EXACT', 'UNDIRECTED', NULL, '{}',
                              'test.matcher', '1', 1, 'hash', '{}', 1)
                    """);
            assertEquals(6, count(connection, "SELECT COUNT(*) FROM media_relationship"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("""
                    INSERT INTO media_relationship (
                        content_record_a_id, content_record_b_id, relationship_type, direction,
                        confidence, evidence_json, matcher_id, matcher_version,
                        configuration_version, configuration_hash, configuration_json, created_at_ms
                    ) VALUES (1, 2, 'CROP', 'B_TO_A', 0.5, '{"changed":true}',
                              'test.matcher', '1', 1, 'hash', '{"different":true}', 200)
                    """));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void indexesSupportEitherEndpointAndTypeQueries() throws Exception {
        String url = freshDatabase("indexes.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            assertEquals(List.of("idx_media_relationship_a_type", "idx_media_relationship_b_type",
                    "idx_media_relationship_type_endpoints"), rows(connection, """
                    SELECT name FROM pragma_index_list('media_relationship')
                    WHERE origin = 'c' ORDER BY name
                    """));
            assertEquals(List.of("content_record_a_id", "relationship_type"), rows(connection,
                    "SELECT name FROM pragma_index_info('idx_media_relationship_a_type') ORDER BY seqno"));
            assertEquals(List.of("content_record_b_id", "relationship_type"), rows(connection,
                    "SELECT name FROM pragma_index_info('idx_media_relationship_b_type') ORDER BY seqno"));
            assertEquals(List.of("relationship_type", "content_record_a_id", "content_record_b_id"), rows(connection,
                    "SELECT name FROM pragma_index_info('idx_media_relationship_type_endpoints') ORDER BY seqno"));
        }
    }

    private String freshDatabase(String name) throws SQLException {
        String url = url(name);
        migrate(url, "8");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (1, 42, 1), (2, 42, 1)
                    """);
        }
        return url;
    }

    private static void insert(Connection connection, String matcher, String version,
            long configVersion, String hash) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO media_relationship (
                    content_record_a_id, content_record_b_id, relationship_type, direction,
                    confidence, evidence_json, matcher_id, matcher_version,
                    configuration_version, configuration_hash, configuration_json, created_at_ms
                ) VALUES (1, 2, 'CROP', 'UNDIRECTED', NULL, '{}', ?, ?, ?, ?, '{}', 1)
                """)) {
            statement.setString(1, matcher);
            statement.setString(2, version);
            statement.setLong(3, configVersion);
            statement.setString(4, hash);
            statement.executeUpdate();
        }
    }

    private static void assertInvalidUpdate(Connection connection, String assignment) {
        assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                "UPDATE media_relationship SET " + assignment));
    }

    private static void seedCatalog(Connection connection) throws SQLException {
        for (String sql : List.of(
                """
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'anchor', 'key', 'ACTIVE', 'ACCEPTED', 3, '{}', 1, 20)
                """,
                """
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    root_path_dialect, bound_location_context_id, binding_evidence_json, created_at_ms, updated_at_ms)
                VALUES (10, 'Bound', '/archive', 'lk1:exact', 4, 'unix',
                    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '{"original":true}', 2, 25)
                """,
                """
                INSERT INTO source_binding_period (source_id, bound_source_location_revision,
                    location_context_id, root_path_dialect, root_path, root_path_key, binding_evidence_json, bound_at_ms)
                VALUES (10, 4, 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'unix', '/archive', 'lk1:exact',
                    '{"original":true}', 25)
                """,
                "INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (30, 42, 4), (31, 42, 4)",
                """
                INSERT INTO file_entry (id, location_identity_status, current_content_id,
                    size_bytes, observation_revision, first_seen_at_ms, last_seen_at_ms)
                VALUES (40, 'UNRESOLVED', 30, 42, 2, 5, 6)
                """,
                """
                INSERT INTO source_membership (id, source_id, file_entry_id, relative_path,
                    path_key, applicability_status, presence_status, membership_revision,
                    observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms)
                VALUES (50, 10, 40, 'photo.jpg', 'photo.jpg', 'ACTIVE', 'PRESENT', 1, 2, 5, 6)
                """,
                """
                INSERT INTO analysis_record (id, content_record_id, analysis_type, analyzer_id,
                    analyzer_version, configuration_version, configuration_hash, configuration_json, status, created_at_ms)
                VALUES (60, 30, 'CONTENT_HASH', 'builtin.sha256', '1', 1, 'hash', '{}', 'COMPLETED', 7),
                       (61, 31, 'CONTENT_HASH', 'builtin.sha256', '1', 1, 'hash', '{}', 'COMPLETED', 7)
                """,
                "INSERT INTO content_hash VALUES (60, 'SHA-256', '" + "a".repeat(64) + "'), (61, 'SHA-256', '" + "a".repeat(64) + "')")) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
    }

    private static Map<String, List<String>> catalogSnapshot(Connection connection) throws SQLException {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : rows(connection, """
                SELECT name FROM sqlite_schema WHERE type = 'table'
                AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name
                """)) {
            snapshot.put(table, rows(connection, "SELECT * FROM " + table + " ORDER BY rowid"));
        }
        return snapshot;
    }

    private String url(String name) {
        return "jdbc:sqlite:" + directory.resolve(name) + "?foreign_keys=on";
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target)).load().migrate();
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
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
