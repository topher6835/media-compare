package io.github.topher6835.mediacompare.scan;

import org.sqlite.SQLiteException;
import org.sqlite.SQLiteErrorCode;

/** Only these exact SQLite unique-column failures are admission/replay conflicts. */
final class IndexingConstraints {
    private IndexingConstraints() {}

    static boolean uniqueColumn(Throwable failure, String column) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLiteException sqlite
                    && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                    && sqlite.getMessage().endsWith("(UNIQUE constraint failed: " + column + ")")) {
                return true;
            }
        }
        return false;
    }
}
