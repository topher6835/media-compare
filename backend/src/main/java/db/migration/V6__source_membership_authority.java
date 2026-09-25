package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Replaces Source-owned FileEntries with source-independent entries and historical memberships. */
public class V6__source_membership_authority extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        require(scalar(connection, "PRAGMA foreign_keys") == 1, "SQLite foreign keys are disabled");
        require(scalar(connection, """
                SELECT COUNT(*) FROM file_entry
                WHERE presence_status NOT IN ('PRESENT', 'MISSING')
                """) == 0, "V5 FileEntry has unknown presence status");
        require(scalar(connection, """
                SELECT COUNT(*) FROM file_entry AS entry
                JOIN scan_run_source AS run_source ON run_source.id = entry.last_seen_scan_run_source_id
                WHERE run_source.source_id <> entry.source_id
                """) == 0, "V5 FileEntry scan provenance disagrees with its Source");

        execute(connection, "CREATE TABLE _v6_file_entry_seed AS SELECT * FROM file_entry");
        long oldCount = scalar(connection, "SELECT COUNT(*) FROM _v6_file_entry_seed");

        execute(connection, """
                CREATE TABLE file_entry_v6 (
                    id INTEGER PRIMARY KEY,
                    location_identity_status TEXT NOT NULL
                        CHECK (location_identity_status IN ('UNRESOLVED', 'RESOLVED')),
                    location_context_id TEXT COLLATE BINARY
                        REFERENCES location_context(id) ON DELETE RESTRICT,
                    location_path TEXT,
                    location_key TEXT COLLATE BINARY,
                    current_content_id INTEGER
                        REFERENCES content_record(id) ON DELETE RESTRICT,
                    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
                    modified_time_epoch_second INTEGER,
                    modified_time_nano INTEGER CHECK (modified_time_nano BETWEEN 0 AND 999999999),
                    extension_key TEXT,
                    observation_revision INTEGER NOT NULL DEFAULT 0 CHECK (observation_revision >= 0),
                    first_seen_at_ms INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    CHECK (
                        (modified_time_epoch_second IS NULL AND modified_time_nano IS NULL)
                        OR (modified_time_epoch_second IS NOT NULL AND modified_time_nano IS NOT NULL)
                    ),
                    CHECK (
                        (location_identity_status = 'UNRESOLVED'
                            AND location_context_id IS NULL AND location_path IS NULL AND location_key IS NULL)
                        OR (location_identity_status = 'RESOLVED'
                            AND location_context_id IS NOT NULL AND location_path IS NOT NULL
                            AND location_key IS NOT NULL)
                    )
                )
                """);
        execute(connection, """
                INSERT INTO file_entry_v6 (
                    id, location_identity_status, current_content_id, size_bytes,
                    modified_time_epoch_second, modified_time_nano, extension_key,
                    observation_revision, first_seen_at_ms, last_seen_at_ms
                )
                SELECT id, 'UNRESOLVED', current_content_id, size_bytes,
                       modified_time_epoch_second, modified_time_nano, extension_key,
                       observation_revision, first_seen_at_ms, last_seen_at_ms
                FROM _v6_file_entry_seed
                """);
        require(scalar(connection, "SELECT COUNT(*) FROM file_entry_v6") == oldCount,
                "FileEntry count changed during V6 copy");
        require(scalar(connection, """
                SELECT COUNT(*) FROM _v6_file_entry_seed AS old
                LEFT JOIN file_entry_v6 AS fresh ON fresh.id = old.id
                WHERE fresh.id IS NULL OR fresh.current_content_id IS NOT old.current_content_id
                   OR fresh.size_bytes <> old.size_bytes
                   OR fresh.modified_time_epoch_second IS NOT old.modified_time_epoch_second
                   OR fresh.modified_time_nano IS NOT old.modified_time_nano
                   OR fresh.extension_key IS NOT old.extension_key
                   OR fresh.observation_revision <> old.observation_revision
                   OR fresh.first_seen_at_ms <> old.first_seen_at_ms
                   OR fresh.last_seen_at_ms <> old.last_seen_at_ms
                """) == 0, "FileEntry values changed during V6 copy");

        // No V1-V5 table has an incoming FK to file_entry. Keep FK enforcement enabled.
        execute(connection, "DROP TABLE file_entry");
        execute(connection, "ALTER TABLE file_entry_v6 RENAME TO file_entry");
        execute(connection, """
                CREATE TABLE source_membership (
                    id INTEGER PRIMARY KEY,
                    source_id INTEGER NOT NULL REFERENCES source(id) ON DELETE RESTRICT,
                    file_entry_id INTEGER NOT NULL REFERENCES file_entry(id) ON DELETE RESTRICT,
                    relative_path TEXT NOT NULL,
                    path_key TEXT COLLATE BINARY NOT NULL,
                    applicability_status TEXT NOT NULL
                        CHECK (applicability_status IN ('ACTIVE', 'RETIRED')),
                    presence_status TEXT NOT NULL
                        CHECK (presence_status IN ('PRESENT', 'MISSING')),
                    membership_revision INTEGER NOT NULL DEFAULT 0 CHECK (membership_revision >= 0),
                    observed_file_entry_revision INTEGER NOT NULL
                        CHECK (observed_file_entry_revision >= 0),
                    first_seen_at_ms INTEGER NOT NULL,
                    last_seen_at_ms INTEGER NOT NULL,
                    last_positive_scan_run_source_id INTEGER
                        REFERENCES scan_run_source(id) ON DELETE SET NULL,
                    last_positive_traversal_generation INTEGER
                        CHECK (last_positive_traversal_generation > 0),
                    observed_source_location_revision INTEGER
                        CHECK (observed_source_location_revision >= 0),
                    observed_location_context_revision INTEGER
                        CHECK (observed_location_context_revision >= 0),
                    UNIQUE (source_id, file_entry_id),
                    CHECK (
                        (observed_source_location_revision IS NULL
                            AND observed_location_context_revision IS NULL)
                        OR (observed_source_location_revision IS NOT NULL
                            AND observed_location_context_revision IS NOT NULL)
                    )
                )
                """);
        execute(connection, """
                INSERT INTO source_membership (
                    source_id, file_entry_id, relative_path, path_key, applicability_status,
                    presence_status, membership_revision, observed_file_entry_revision,
                    first_seen_at_ms, last_seen_at_ms, last_positive_scan_run_source_id,
                    last_positive_traversal_generation
                )
                SELECT source_id, id, relative_path, path_key, 'ACTIVE', presence_status,
                       0, observation_revision, first_seen_at_ms, last_seen_at_ms,
                       last_seen_scan_run_source_id, last_seen_traversal_generation
                FROM _v6_file_entry_seed
                """);
        execute(connection, """
                CREATE UNIQUE INDEX uq_file_entry_resolved_location
                ON file_entry (location_context_id, location_key)
                WHERE location_identity_status = 'RESOLVED'
                """);
        execute(connection, """
                CREATE UNIQUE INDEX uq_source_membership_active_path
                ON source_membership (source_id, path_key)
                WHERE applicability_status = 'ACTIVE'
                """);
        execute(connection, """
                CREATE INDEX idx_file_entry_content ON file_entry (current_content_id)
                """);
        execute(connection, """
                CREATE INDEX idx_file_entry_extension_content
                ON file_entry (extension_key, current_content_id)
                """);
        execute(connection, """
                CREATE INDEX idx_source_membership_file
                ON source_membership (file_entry_id, applicability_status, presence_status)
                """);
        execute(connection, """
                CREATE INDEX idx_source_membership_source_reconciliation
                ON source_membership (source_id, applicability_status, presence_status,
                                      last_positive_scan_run_source_id,
                                      last_positive_traversal_generation)
                """);
        execute(connection, """
                CREATE UNIQUE INDEX uq_job_v3_scan_run
                ON job (scan_run_id)
                WHERE job_type = 'SCAN' AND execution_version = 3
                """);
        execute(connection, """
                CREATE UNIQUE INDEX uq_job_active_v2_v3_scan
                ON job (job_type)
                WHERE job_type = 'SCAN' AND execution_version IN (2, 3)
                  AND status IN ('PENDING', 'RUNNING')
                """);

        require(scalar(connection, "SELECT COUNT(*) FROM file_entry") == oldCount,
                "FileEntry count changed after V6 rebuild");
        require(scalar(connection, "SELECT COUNT(*) FROM source_membership") == oldCount,
                "Membership count differs from V5 FileEntry count");
        require(scalar(connection, """
                SELECT COUNT(*) FROM _v6_file_entry_seed AS old
                LEFT JOIN source_membership AS membership ON membership.file_entry_id = old.id
                WHERE membership.id IS NULL
                   OR membership.source_id <> old.source_id
                   OR membership.relative_path <> old.relative_path
                   OR membership.path_key <> old.path_key
                   OR membership.presence_status <> old.presence_status
                   OR membership.applicability_status <> 'ACTIVE'
                   OR membership.membership_revision <> 0
                   OR membership.observed_file_entry_revision <> old.observation_revision
                   OR membership.first_seen_at_ms <> old.first_seen_at_ms
                   OR membership.last_seen_at_ms <> old.last_seen_at_ms
                   OR membership.last_positive_scan_run_source_id IS NOT old.last_seen_scan_run_source_id
                   OR membership.last_positive_traversal_generation IS NOT old.last_seen_traversal_generation
                   OR membership.observed_source_location_revision IS NOT NULL
                   OR membership.observed_location_context_revision IS NOT NULL
                """) == 0, "Membership backfill changed V5 relationship or provenance");
        require(scalar(connection, """
                SELECT COUNT(*) FROM file_entry
                WHERE location_identity_status <> 'UNRESOLVED' OR location_context_id IS NOT NULL
                   OR location_path IS NOT NULL OR location_key IS NOT NULL
                """) == 0, "V5 FileEntry gained unsupported resolved identity");
        require(scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") == 0,
                "V6 migration left broken foreign keys");
        execute(connection, "DROP TABLE _v6_file_entry_seed");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("V6 assertion returned no result: " + sql);
            }
            return result.getLong(1);
        }
    }

    private static void require(boolean condition, String message) throws SQLException {
        if (!condition) {
            throw new SQLException(message);
        }
    }
}
