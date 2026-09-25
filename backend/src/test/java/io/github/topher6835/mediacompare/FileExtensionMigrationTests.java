package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileExtensionMigrationTests {

    @TempDir
    Path temporaryDirectory;

    @Test
    void v2BackfillsExistingFileEntriesAndCreatesExtensionIndex() throws Exception {
        String databaseUrl = "jdbc:sqlite:" + temporaryDirectory.resolve("migration.db") + "?foreign_keys=on";
        Flyway.configure()
                .dataSource(databaseUrl, null, null)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("photo.JPG", "jpg");
        expected.put(".gitignore", null);
        expected.put("photo.", null);
        expected.put("README", null);
        expected.put(".config.json", "json");
        expected.put("folder/archive.tar.gz", "gz");
        expected.put("folder/file.UnKnOwN", "unknown");
        for (int index = 0; index < 1_003; index++) {
            String relativePath = switch (index % 5) {
                case 0 -> "batch/entry-%04d.JPG".formatted(index);
                case 1 -> "batch/.config-%04d.json".formatted(index);
                case 2 -> "batch/no-extension-%04d".formatted(index);
                case 3 -> "batch/trailing-dot-%04d.".formatted(index);
                default -> "batch/archive-%04d.tar.GZ".formatted(index);
            };
            String extension = switch (index % 5) {
                case 0 -> "jpg";
                case 1 -> "json";
                case 2, 3 -> null;
                default -> "gz";
            };
            expected.put(relativePath, extension);
        }

        try (var connection = DriverManager.getConnection(databaseUrl)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO source (
                        name, root_path, root_path_key, location_revision, created_at_ms, updated_at_ms
                    ) VALUES ('Source', '/unavailable', '/unavailable', 0, 1, 1)
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO file_entry (
                        source_id, relative_path, path_key, presence_status,
                        size_bytes, first_seen_at_ms, last_seen_at_ms
                    ) VALUES (1, ?, ?, 'PRESENT', 1, 1, 1)
                    """)) {
                for (String path : expected.keySet()) {
                    insert.setString(1, path);
                    insert.setString(2, path);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }

        Flyway.configure()
                .dataSource(databaseUrl, null, null)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(databaseUrl);
                var rows = connection.createStatement().executeQuery("""
                        SELECT relative_path, extension_key
                        FROM file_entry
                        ORDER BY id
                        """)) {
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                assertTrue(rows.next());
                assertEquals(entry.getKey(), rows.getString("relative_path"));
                if (entry.getValue() == null) {
                    assertNull(rows.getString("extension_key"));
                } else {
                    assertEquals(entry.getValue(), rows.getString("extension_key"));
                }
            }
            assertFalse(rows.next(), "V2 should backfill every row exactly once across keyset batches");
        }

        try (var connection = DriverManager.getConnection(databaseUrl);
                var indexes = connection.createStatement().executeQuery("PRAGMA index_list('file_entry')")) {
            boolean found = false;
            while (indexes.next()) {
                found |= "idx_file_entry_extension_content".equals(indexes.getString("name"));
            }
            assertTrue(found);
        }

        List<String> representativePaths = List.of(
                ".gitignore", ".config.json", "archive.tar.gz", "photo.JPG");
        List<String> expectedExtensions = List.of("<null>", "json", "gz", "jpg");
        for (int index = 0; index < representativePaths.size(); index++) {
            String runtimeExtension = FileExtensionNormalizer.fromRelativePath(representativePaths.get(index));
            assertEquals(expectedExtensions.get(index),
                    runtimeExtension == null ? "<null>" : runtimeExtension);
        }
    }
}
