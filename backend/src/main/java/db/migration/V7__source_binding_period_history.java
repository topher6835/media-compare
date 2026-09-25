package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Records the current bound period without changing Source binding authority. */
public class V7__source_binding_period_history extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        require(scalar(connection, "PRAGMA foreign_keys") == 1, "SQLite foreign keys are disabled");
        require(scalar(connection, """
                SELECT COUNT(*) FROM source
                WHERE (bound_location_context_id IS NULL AND
                        (root_path_dialect IS NOT NULL OR binding_evidence_json IS NOT NULL))
                   OR (bound_location_context_id IS NOT NULL AND
                        (root_path_dialect IS NULL OR binding_evidence_json IS NULL))
                """) == 0, "Source has a partial binding shape");

        execute(connection, """
                CREATE TABLE source_binding_period (
                    id INTEGER PRIMARY KEY,
                    source_id INTEGER NOT NULL REFERENCES source(id) ON DELETE RESTRICT,
                    bound_source_location_revision INTEGER NOT NULL
                        CHECK (bound_source_location_revision >= 0),
                    location_context_id TEXT COLLATE BINARY NOT NULL
                        REFERENCES location_context(id) ON DELETE RESTRICT,
                    root_path_dialect TEXT NOT NULL,
                    root_path TEXT NOT NULL,
                    root_path_key TEXT COLLATE BINARY NOT NULL,
                    binding_evidence_json TEXT NOT NULL,
                    bound_at_ms INTEGER NOT NULL,
                    unbound_source_location_revision INTEGER
                        CHECK (unbound_source_location_revision >= 0),
                    unbound_at_ms INTEGER,
                    UNIQUE (source_id, bound_source_location_revision),
                    CHECK ((unbound_source_location_revision IS NULL AND unbound_at_ms IS NULL)
                        OR (unbound_source_location_revision IS NOT NULL AND unbound_at_ms IS NOT NULL)),
                    CHECK (unbound_source_location_revision IS NULL
                        OR unbound_source_location_revision > bound_source_location_revision),
                    CHECK (unbound_at_ms IS NULL OR unbound_at_ms >= bound_at_ms)
                )
                """);
        execute(connection, """
                CREATE UNIQUE INDEX uq_source_binding_period_open
                ON source_binding_period (source_id)
                WHERE unbound_source_location_revision IS NULL
                """);
        execute(connection, """
                INSERT INTO source_binding_period (
                    source_id, bound_source_location_revision, location_context_id,
                    root_path_dialect, root_path, root_path_key, binding_evidence_json,
                    bound_at_ms
                )
                SELECT id, location_revision, bound_location_context_id,
                       root_path_dialect, root_path, root_path_key, binding_evidence_json,
                       updated_at_ms
                FROM source WHERE bound_location_context_id IS NOT NULL
                """);
        require(scalar(connection, """
                SELECT COUNT(*) FROM source AS source
                LEFT JOIN source_binding_period AS period ON period.source_id = source.id
                WHERE (source.bound_location_context_id IS NULL AND period.id IS NOT NULL)
                   OR (source.bound_location_context_id IS NOT NULL AND
                       (period.id IS NULL
                        OR period.bound_source_location_revision <> source.location_revision
                        OR period.location_context_id <> source.bound_location_context_id
                        OR period.root_path_dialect <> source.root_path_dialect
                        OR period.root_path <> source.root_path
                        OR period.root_path_key <> source.root_path_key
                        OR period.binding_evidence_json <> source.binding_evidence_json
                        OR period.bound_at_ms <> source.updated_at_ms
                        OR period.unbound_source_location_revision IS NOT NULL
                        OR period.unbound_at_ms IS NOT NULL))
                """) == 0, "V7 binding-period backfill disagrees with Source");
        require(scalar(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") == 0,
                "V7 migration left broken foreign keys");
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
                throw new SQLException("V7 assertion returned no result: " + sql);
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
