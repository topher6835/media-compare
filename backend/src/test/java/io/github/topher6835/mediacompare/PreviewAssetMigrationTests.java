package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

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

class PreviewAssetMigrationTests {
    @TempDir Path directory;

    @Test
    void v8ToV9PreservesEveryExistingApplicationTableAndRow() throws Exception {
        String url = url("preservation.db");
        migrate(url, "8");
        Map<String, List<String>> before;
        try (Connection connection = DriverManager.getConnection(url)) {
            seedCatalog(connection);
            before = snapshot(connection);
            assertEquals(15, before.size());
            assertTrue(before.values().stream().allMatch(rows -> !rows.isEmpty()));
        }
        migrate(url, "9");
        try (Connection connection = DriverManager.getConnection(url)) {
            Map<String, List<String>> after = snapshot(connection);
            assertEquals(16, after.size());
            assertEquals(List.of(), after.remove("preview_asset"));
            assertEquals(before, after);
            assertEquals(List.of("0"), rows(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void constrainsIdentityEvidenceDimensionsTimeAndSafePathsWithoutRigidKinds() throws Exception {
        try (Connection connection = fresh("constraints.db")) {
            insert(connection, "a".repeat(64), "test.preview", "1", 1, "hash");
            for (String assignment : List.of("id = 0", "file_entry_id = 0", "content_record_id = 0",
                    "file_entry_id = 999", "content_record_id = 999", "file_observation_revision = -1",
                    "file_observation_revision = 1.5", "source_size_bytes = -1", "source_size_bytes = 1.5",
                    "source_modified_time_epoch_second = NULL", "source_modified_time_epoch_second = 1.5",
                    "source_modified_time_nano = NULL", "source_modified_time_nano = -1",
                    "source_modified_time_nano = 1000000000", "source_modified_time_nano = 1.5",
                    "pixel_width = 0", "pixel_height = -1", "pixel_width = 1.5", "asset_size_bytes = 0",
                    "created_at_ms = -1", "configuration_version = 0", "configuration_version = 1.5",
                    "preview_kind = char(9)", "generator_id = char(10)", "generator_version = char(13)",
                    "configuration_hash = char(9)", "media_type = char(10)",
                    "preview_kind = ''", "generator_id = ' '", "generator_version = ''", "configuration_hash = ''",
                    "media_type = ''", "asset_key = 'bad'", "asset_key = '" + "A".repeat(64) + "'")) {
                assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                        "UPDATE preview_asset SET " + assignment), assignment);
            }
            for (String path : List.of("", "../file.png", "a/../b.png", "/file.png", "a//b.png", "a/./b.png",
                    "a\\b.png", "C:/file.png", "a/", "safe.png\u0000hidden", "x".repeat(513))) {
                assertInvalidValue(connection, "relative_path", path);
            }
            assertInvalidValue(connection, "asset_key", "a".repeat(64) + "\u0000hidden");
            connection.createStatement().executeUpdate("""
                    UPDATE preview_asset SET preview_kind = 'FUTURE_VIDEO_POSTER',
                        source_modified_time_epoch_second = -1, source_modified_time_nano = 999999999,
                        file_observation_revision = 0, source_size_bytes = 0, created_at_ms = 0
                    """);
            assertEquals(List.of("0"), rows(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void requiresBoundedValidJsonObjects() throws Exception {
        try (Connection connection = fresh("json.db")) {
            insert(connection, "a".repeat(64), "test.preview", "1", 1, "hash");
            for (String json : List.of("", " ", "invalid", "[]", "null", "{} {}",
                    "{\"x\":\"" + "é".repeat(65536) + "\"}")) {
                assertInvalidValue(connection, "configuration_json", json);
            }
            try (var statement = connection.prepareStatement("UPDATE preview_asset SET configuration_json = ?")) {
                statement.setString(1, "{\"x\":\"" + "x".repeat(131072 - 8) + "\"}");
                assertEquals(1, statement.executeUpdate());
            }
        }
    }

    @Test
    void independentlyEnforcesKeyAndLogicalUniquenessWhileDefinitionsCoexist() throws Exception {
        try (Connection connection = fresh("uniqueness.db")) {
            insert(connection, "a".repeat(64), "test.preview", "1", 1, "hash");
            assertThrows(SQLException.class, () -> insert(connection, "a".repeat(64), "test.preview", "2", 1, "hash"));
            assertThrows(SQLException.class, () -> insert(connection, "b".repeat(64), "test.preview", "1", 1, "hash"));
            insert(connection, "b".repeat(64), "test.preview", "2", 1, "hash");
            insert(connection, "c".repeat(64), "test.preview", "1", 2, "hash");
            insert(connection, "d".repeat(64), "test.preview", "1", 1, "other-hash");
            insert(connection, "e".repeat(64), "other.preview", "1", 1, "hash");
            assertEquals(List.of("5"), rows(connection, "SELECT COUNT(*) FROM preview_asset"));
        }
    }

    @Test
    void cacheProvenanceForeignKeysCascadeWithoutChangingCatalogDeletionRules() throws Exception {
        try (Connection connection = fresh("cascade.db")) {
            assertEquals(List.of("content_record|content_record_id|id|CASCADE", "file_entry|file_entry_id|id|CASCADE"),
                    rows(connection, """
                    SELECT "table", "from", "to", on_delete
                    FROM pragma_foreign_key_list('preview_asset') ORDER BY "from"
                    """));
            insert(connection, "a".repeat(64), "test.preview", "1", 1, "hash");
            connection.createStatement().executeUpdate("DELETE FROM file_entry WHERE id = 7");
            assertEquals(List.of("0"), rows(connection, "SELECT COUNT(*) FROM preview_asset"));
            assertEquals(List.of("1"), rows(connection, "SELECT COUNT(*) FROM content_record"));
            seedFileEntry(connection);
            insert(connection, "a".repeat(64), "test.preview", "1", 1, "hash");
            // Existing FileEntry -> ContentRecord RESTRICT still protects current catalog content.
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("DELETE FROM content_record WHERE id = 11"));
            connection.createStatement().executeUpdate("UPDATE file_entry SET current_content_id = NULL");
            connection.createStatement().executeUpdate("DELETE FROM content_record WHERE id = 11");
            assertEquals(List.of("0"), rows(connection, "SELECT COUNT(*) FROM preview_asset"));
            assertEquals(List.of("1"), rows(connection, "SELECT COUNT(*) FROM file_entry"));
            assertEquals(List.of("0"), rows(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void uniqueIndexesCoverCurrentAndKeyLookupAndOnlyOneCleanupIndexIsAdded() throws Exception {
        try (Connection connection = fresh("indexes.db")) {
            assertEquals(List.of("idx_preview_asset_created_at"), rows(connection, """
                    SELECT name FROM pragma_index_list('preview_asset') WHERE origin = 'c' ORDER BY name
                    """));
            assertEquals(List.of("created_at_ms", "id"), rows(connection,
                    "SELECT name FROM pragma_index_info('idx_preview_asset_created_at') ORDER BY seqno"));
            List<String> uniqueIndexes = rows(connection,
                    "SELECT name FROM pragma_index_list('preview_asset') WHERE origin = 'u' ORDER BY name");
            assertEquals(2, uniqueIndexes.size());
            var columns = uniqueIndexes.stream().map(index -> {
                try {
                    return rows(connection, "SELECT name FROM pragma_index_info('" + index + "') ORDER BY seqno");
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            }).toList();
            assertTrue(columns.contains(List.of("asset_key")));
            assertTrue(columns.contains(List.of("file_entry_id", "preview_kind", "content_record_id",
                    "file_observation_revision", "source_size_bytes", "source_modified_time_epoch_second",
                    "source_modified_time_nano", "generator_id", "generator_version", "configuration_version", "configuration_hash")));
        }
    }

    private Connection fresh(String name) throws SQLException {
        String url = url(name);
        migrate(url, "9");
        Connection connection = DriverManager.getConnection(url);
        connection.createStatement().executeUpdate("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (11,42,1)");
        seedFileEntry(connection);
        return connection;
    }

    private static void seedFileEntry(Connection connection) throws SQLException {
        connection.createStatement().executeUpdate("""
                INSERT INTO file_entry (id, location_identity_status, current_content_id, size_bytes,
                    observation_revision, modified_time_epoch_second, modified_time_nano, first_seen_at_ms, last_seen_at_ms)
                VALUES (7, 'UNRESOLVED', 11, 42, 3, 1700000000, 123456789, 1, 2)
                """);
    }

    private static void insert(Connection connection, String key, String generator, String version,
            long configurationVersion, String hash) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO preview_asset (asset_key, file_entry_id, content_record_id, file_observation_revision,
                    source_size_bytes, source_modified_time_epoch_second, source_modified_time_nano,
                    preview_kind, generator_id, generator_version, configuration_version, configuration_hash,
                    configuration_json, relative_path, media_type, pixel_width, pixel_height, asset_size_bytes, created_at_ms)
                VALUES (?,7,11,3,42,1700000000,123456789,'SMALL_THUMBNAIL',?,?,?,?,'{}','safe/file.png','image/png',120,80,6,1)
                """)) {
            statement.setString(1, key);
            statement.setString(2, generator);
            statement.setString(3, version);
            statement.setLong(4, configurationVersion);
            statement.setString(5, hash);
            statement.executeUpdate();
        }
    }

    private static void assertInvalidValue(Connection connection, String column, String value) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE preview_asset SET " + column + " = ?")) {
            statement.setString(1, value);
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private static void seedCatalog(Connection connection) throws SQLException {
        for (String sql : List.of(
                """
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key, lifecycle_status,
                    continuity_status, revision, continuity_evidence_json, created_at_ms, updated_at_ms)
                VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','anchor','key','ACTIVE','ACCEPTED',3,'{}',1,20)
                """,
                """
                INSERT INTO source (id,name,root_path,root_path_key,location_revision,root_path_dialect,
                    bound_location_context_id,binding_evidence_json,created_at_ms,updated_at_ms)
                VALUES (1,'Bound','/archive','lk1:exact',4,'unix','aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','{}',2,25)
                """,
                """
                INSERT INTO source_binding_period (source_id,bound_source_location_revision,location_context_id,
                    root_path_dialect,root_path,root_path_key,binding_evidence_json,bound_at_ms)
                VALUES (1,4,'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','unix','/archive','lk1:exact','{}',25)
                """,
                "INSERT INTO content_record (id,size_bytes,created_at_ms) VALUES (11,42,1),(12,42,1)",
                """
                INSERT INTO file_entry (id,location_identity_status,current_content_id,size_bytes,
                    observation_revision,first_seen_at_ms,last_seen_at_ms) VALUES (7,'UNRESOLVED',11,42,3,1,2)
                """,
                """
                INSERT INTO source_membership (source_id,file_entry_id,relative_path,path_key,applicability_status,
                    presence_status,observed_file_entry_revision,first_seen_at_ms,last_seen_at_ms)
                VALUES (1,7,'photo.png','photo.png','ACTIVE','PRESENT',3,1,2)
                """,
                "INSERT INTO working_set (id,name,created_at_ms,updated_at_ms) VALUES (1,'review',1,2)",
                "INSERT INTO working_set_content VALUES (1,11,2)",
                """
                INSERT INTO scan_run (id,request_type,status,working_set_id,options_version,options_json,created_at_ms)
                VALUES (1,'INDEX','COMPLETED',1,1,'{}',1)
                """,
                "INSERT INTO scan_run_source (id,scan_run_id,source_id,status,source_location_revision) VALUES (1,1,1,'COMPLETED',4)",
                "INSERT INTO job (id,scan_run_id,job_type,status,created_at_ms) VALUES (1,1,'SCAN','COMPLETED',1)",
                "INSERT INTO job_stage (job_id,stage_type,status,created_at_ms) VALUES (1,'CONTENT_HASHING','COMPLETED',1)",
                """
                INSERT INTO analysis_record (id,content_record_id,analysis_type,analyzer_id,analyzer_version,
                    configuration_version,configuration_hash,configuration_json,status,created_at_ms)
                VALUES (1,11,'CONTENT_HASH','builtin.sha256','1',1,'hash','{}','COMPLETED',1)
                """,
                "INSERT INTO content_hash VALUES (1,'SHA-256','" + "a".repeat(64) + "')",
                """
                INSERT INTO media_relationship (content_record_a_id,content_record_b_id,relationship_type,direction,
                    evidence_json,matcher_id,matcher_version,configuration_version,configuration_hash,configuration_json,created_at_ms)
                VALUES (11,12,'EXACT','UNDIRECTED','{}','test.matcher','1',1,'hash','{}',1)
                """)) {
            connection.createStatement().executeUpdate(sql);
        }
    }

    private static Map<String, List<String>> snapshot(Connection connection) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : rows(connection, """
                SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                    AND name <> 'flyway_schema_history' ORDER BY name
                """)) {
            result.put(table, rows(connection, "SELECT * FROM " + table + " ORDER BY rowid"));
        }
        return result;
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
            int columns = row.getMetaData().getColumnCount();
            while (row.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= columns; column++) {
                    values.add(String.valueOf(row.getString(column)));
                }
                result.add(String.join("|", values));
            }
        }
        return result;
    }

    private String url(String filename) {
        return "jdbc:sqlite:" + directory.resolve(filename) + "?foreign_keys=on";
    }

    private static void migrate(String url, String version) {
        Flyway.configure().dataSource(url,null,null).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }
}
