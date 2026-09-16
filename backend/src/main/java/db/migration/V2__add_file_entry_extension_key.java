package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2__add_file_entry_extension_key extends BaseJavaMigration {

    private static final int BATCH_SIZE = 500;

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE file_entry ADD COLUMN extension_key TEXT");
        }

        backfillExtensions(connection);

        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE INDEX idx_file_entry_extension_content
                    ON file_entry (extension_key, current_content_id)
                    """);
        }
    }

    private static void backfillExtensions(Connection connection) throws SQLException {
        long afterId = 0;
        while (true) {
            List<PathRow> rows = readBatch(connection, afterId);
            if (rows.isEmpty()) {
                return;
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE file_entry SET extension_key = ? WHERE id = ?")) {
                for (PathRow row : rows) {
                    update.setString(1, extensionKey(row.relativePath()));
                    update.setLong(2, row.id());
                    update.addBatch();
                }
                update.executeBatch();
            }
            afterId = rows.getLast().id();
        }
    }

    // Keep this historical rule snapshot private to V2. Runtime normalization may evolve only
    // alongside a later migration that explicitly changes persisted extension semantics.
    private static String extensionKey(String relativePath) {
        int basenameStart = relativePath.lastIndexOf('/') + 1;
        int lastDot = relativePath.lastIndexOf('.');
        if (lastDot < basenameStart || lastDot == basenameStart || lastDot == relativePath.length() - 1) {
            return null;
        }
        return relativePath.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    private static List<PathRow> readBatch(Connection connection, long afterId) throws SQLException {
        List<PathRow> rows = new ArrayList<>(BATCH_SIZE);
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, relative_path
                FROM file_entry
                WHERE id > ?
                ORDER BY id
                LIMIT ?
                """)) {
            query.setLong(1, afterId);
            query.setInt(2, BATCH_SIZE);
            try (ResultSet resultSet = query.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new PathRow(
                            resultSet.getLong("id"),
                            resultSet.getString("relative_path")));
                }
            }
        }
        return rows;
    }

    private record PathRow(long id, String relativePath) {
    }
}
