package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExfatOccurrenceMigrationTests {
    @TempDir Path directory;
    private static final String CONTEXT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TOKEN = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Test
    void v9UpgradePreservesIdsMembershipPreviewContentAndRelationshipForeignKeys() throws Exception {
        String url = url("upgrade.db");
        migrate(url, "9");
        Map<String, List<String>> before;
        try (var connection = DriverManager.getConnection(url)) {
            assertEquals("3.50.3", scalar(connection, "SELECT sqlite_version()"));
            PreviewAssetMigrationTests.seedCatalog(connection);
            PreviewAssetMigrationTests.insert(connection, "a".repeat(64), "test.preview", "1", 1, "hash");
            nativeEntry(connection, 8);
            before = PreviewAssetMigrationTests.snapshot(connection);
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
        migrate(url, "10");
        try (var connection = DriverManager.getConnection(url)) {
            var after = PreviewAssetMigrationTests.snapshot(connection);
            var oldFiles = before.remove("file_entry");
            assertEquals(oldFiles.stream().map(row -> row + "|null|null").toList(), after.remove("file_entry"));
            assertEquals(before, after);
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM preview_asset WHERE file_entry_id=7 AND content_record_id=11"));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM source_membership WHERE file_entry_id=7"));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM media_relationship WHERE content_record_a_id=11 AND content_record_b_id=12"));
            assertThrows(SQLException.class, () -> nativeEntry(connection, 9));
            occurrence(connection, 9, TOKEN, "{}");
            occurrence(connection, 10, "cccccccc-cccc-cccc-cccc-cccccccccccc", "{}");
            assertEquals("3", scalar(connection, "SELECT COUNT(*) FROM file_entry WHERE location_identity_status='RESOLVED'"));
            assertThrows(SQLException.class, () -> occurrence(connection, 11, TOKEN, "{}"));
            assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("""
                    INSERT INTO file_entry (id,location_identity_status,location_context_id,location_path,
                        location_key,size_bytes,first_seen_at_ms,last_seen_at_ms,occurrence_token,observation_evidence_json)
                    SELECT 11,location_identity_status,location_context_id,'another path','another key',
                        size_bytes,first_seen_at_ms,last_seen_at_ms,occurrence_token,observation_evidence_json
                    FROM file_entry WHERE id=9
                    """));
        }
    }

    @Test
    void databaseChecksEnforceUuidPairingResolvedStatusAndUtf8ByteLimits() throws Exception {
        try (var connection = fresh("checks.db")) {
            occurrence(connection, 9, TOKEN, "{}");
            for (String bad : List.of("", "A".repeat(36), TOKEN.toUpperCase(), TOKEN + "x", "1-1-1-1-1",
                    "bbbbbbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb", TOKEN + "\u0000", "é".repeat(36))) {
                invalidText(connection, "occurrence_token", bad);
            }
            invalidText(connection, "observation_evidence_json", "");
            invalidText(connection, "observation_evidence_json", "é".repeat(524289));
            invalidText(connection, "observation_evidence_json", "x".repeat(1048577));
            for (String assignment : List.of("occurrence_token=NULL", "observation_evidence_json=NULL",
                    "occurrence_token=x'0001'", "observation_evidence_json=x'0001'",
                    "location_identity_status='UNRESOLVED', location_context_id=NULL, location_path=NULL, location_key=NULL")) {
                assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                        "UPDATE file_entry SET " + assignment + " WHERE id=9"), assignment);
            }
            setText(connection, "observation_evidence_json", "é".repeat(524288));
            assertEquals("1048576", scalar(connection, "SELECT length(CAST(observation_evidence_json AS BLOB)) FROM file_entry WHERE id=9"));
            assertThrows(SQLException.class, () -> occurrence(connection, 10, null, "{}"));
            assertThrows(SQLException.class, () -> occurrence(connection, 10, "cccccccc-cccc-cccc-cccc-cccccccccccc", null));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void indexesSeparateNativeUniquenessTokenIdentityAndOrderedExfatHistory() throws Exception {
        try (var connection = fresh("indexes.db")) {
            String index = scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='uq_file_entry_resolved_location'");
            assertTrue(index.contains("occurrence_token IS NULL"));
            assertTrue(scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='uq_file_entry_occurrence_token'")
                    .contains("WHERE occurrence_token IS NOT NULL"));
            assertEquals("location_context_id,location_key,id", scalar(connection,
                    "SELECT group_concat(name, ',') FROM pragma_index_info('idx_file_entry_exfat_location')"));
            assertTrue(scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='idx_file_entry_exfat_location'")
                    .contains("occurrence_token IS NOT NULL"));
            assertFalse(scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='file_entry'").contains("AUTOINCREMENT"));
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger'"));
        }
    }

    @Test
    void failedIndexRecreationRollsBackBothColumnsAndOriginalNativeIndex() throws Exception {
        String url = url("rollback.db");
        migrate(url, "9");
        try (var connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("CREATE INDEX uq_file_entry_occurrence_token ON source(name)");
        }
        assertThrows(RuntimeException.class, () -> migrate(url, "10"));
        try (var connection = DriverManager.getConnection(url)) {
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM pragma_table_info('file_entry') WHERE name IN ('occurrence_token','observation_evidence_json')"));
            assertFalse(scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='uq_file_entry_resolved_location'")
                    .contains("occurrence_token"));
        }
    }

    private Connection fresh(String filename) throws SQLException {
        String url = url(filename);
        migrate(url, "10");
        var connection = DriverManager.getConnection(url);
        connection.createStatement().executeUpdate("""
                INSERT INTO location_context (id,anchor_location_path,anchor_location_key,lifecycle_status,
                    continuity_status,revision,created_at_ms,updated_at_ms)
                VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','anchor','key','ACTIVE','REVIEW_REQUIRED',0,1,1)
                """);
        return connection;
    }

    private static void nativeEntry(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO file_entry (id,location_identity_status,location_context_id,location_path,
                    location_key,size_bytes,first_seen_at_ms,last_seen_at_ms)
                VALUES (?,'RESOLVED',?,'path','key',42,1,1)
                """)) {
            statement.setLong(1, id);
            statement.setString(2, CONTEXT);
            statement.executeUpdate();
        }
    }

    private static void occurrence(Connection connection, long id, String token, String receipt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO file_entry (id,location_identity_status,location_context_id,location_path,
                    location_key,size_bytes,first_seen_at_ms,last_seen_at_ms,occurrence_token,observation_evidence_json)
                VALUES (?,'RESOLVED',?,'path','key',42,1,1,?,?)
                """)) {
            statement.setLong(1, id); statement.setString(2, CONTEXT);
            statement.setString(3, token); statement.setString(4, receipt);
            statement.executeUpdate();
        }
    }

    private static void invalidText(Connection connection, String column, String value) {
        assertThrows(SQLException.class, () -> setText(connection, column, value));
    }

    private static void setText(Connection connection, String column, String value) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE file_entry SET " + column + "=? WHERE id=9")) {
            statement.setString(1, value); statement.executeUpdate();
        }
    }

    private static String scalar(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private String url(String filename) { return "jdbc:sqlite:" + directory.resolve(filename) + "?foreign_keys=on"; }
    private static void migrate(String url, String version) {
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }
}
