package io.github.topher6835.mediacompare.analysis;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisRepository {

    private final JdbcTemplate jdbcTemplate;

    public AnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AnalysisRecord insert(AnalysisRecord analysisRecord) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO analysis_record (
                        content_record_id, analysis_type, analyzer_id, analyzer_version,
                        configuration_version, configuration_hash, configuration_json, result_json, status,
                        attempt_count, created_at_ms, started_at_ms, finished_at_ms, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, analysisRecord.contentRecordId());
            statement.setString(2, analysisRecord.analysisType());
            statement.setString(3, analysisRecord.analyzerId());
            statement.setString(4, analysisRecord.analyzerVersion());
            statement.setLong(5, analysisRecord.configurationVersion());
            statement.setString(6, analysisRecord.configurationHash());
            statement.setString(7, analysisRecord.configurationJson());
            statement.setString(8, analysisRecord.resultJson());
            statement.setString(9, analysisRecord.status());
            statement.setLong(10, analysisRecord.attemptCount());
            statement.setLong(11, analysisRecord.createdAtMs());
            setNullableLong(statement, 12, analysisRecord.startedAtMs());
            setNullableLong(statement, 13, analysisRecord.finishedAtMs());
            statement.setString(14, analysisRecord.errorMessage());
            return statement;
        }, keyHolder);

        return new AnalysisRecord(generatedId(keyHolder), analysisRecord.contentRecordId(),
                analysisRecord.analysisType(), analysisRecord.analyzerId(), analysisRecord.analyzerVersion(),
                analysisRecord.configurationVersion(), analysisRecord.configurationHash(),
                analysisRecord.configurationJson(), analysisRecord.resultJson(), analysisRecord.status(), analysisRecord.attemptCount(),
                analysisRecord.createdAtMs(), analysisRecord.startedAtMs(), analysisRecord.finishedAtMs(),
                analysisRecord.errorMessage());
    }

    public Optional<AnalysisRecord> findAnalysisRecordById(long id) {
        return jdbcTemplate.query("SELECT * FROM analysis_record WHERE id = ?",
                (resultSet, rowNumber) -> new AnalysisRecord(
                        resultSet.getLong("id"),
                        resultSet.getLong("content_record_id"),
                        resultSet.getString("analysis_type"),
                        resultSet.getString("analyzer_id"),
                        resultSet.getString("analyzer_version"),
                        resultSet.getLong("configuration_version"),
                        resultSet.getString("configuration_hash"),
                        resultSet.getString("configuration_json"),
                        resultSet.getString("result_json"),
                        resultSet.getString("status"),
                        resultSet.getLong("attempt_count"),
                        resultSet.getLong("created_at_ms"),
                        nullableLong(resultSet, "started_at_ms"),
                        nullableLong(resultSet, "finished_at_ms"),
                        resultSet.getString("error_message")),
                id).stream().findFirst();
    }

    public Optional<AnalysisRecord> findAnalysisRecordByCacheKey(
            long contentRecordId, String analysisType, String analyzerId, String analyzerVersion,
            long configurationVersion, String configurationHash) {
        return jdbcTemplate.query("""
                SELECT * FROM analysis_record
                WHERE content_record_id = ?
                  AND analysis_type = ?
                  AND analyzer_id = ?
                  AND analyzer_version = ?
                  AND configuration_version = ?
                  AND configuration_hash = ?
                """, (resultSet, rowNumber) -> new AnalysisRecord(
                        resultSet.getLong("id"),
                        resultSet.getLong("content_record_id"),
                        resultSet.getString("analysis_type"),
                        resultSet.getString("analyzer_id"),
                        resultSet.getString("analyzer_version"),
                        resultSet.getLong("configuration_version"),
                        resultSet.getString("configuration_hash"),
                        resultSet.getString("configuration_json"),
                        resultSet.getString("result_json"),
                        resultSet.getString("status"),
                        resultSet.getLong("attempt_count"),
                        resultSet.getLong("created_at_ms"),
                        nullableLong(resultSet, "started_at_ms"),
                        nullableLong(resultSet, "finished_at_ms"),
                        resultSet.getString("error_message")),
                contentRecordId, analysisType, analyzerId, analyzerVersion,
                configurationVersion, configurationHash).stream().findFirst();
    }

    public int completeFailedAnalysisRecord(
            long analysisRecordId, String resultJson, long startedAtMs, long finishedAtMs) {
        return jdbcTemplate.update("""
                UPDATE analysis_record
                SET result_json = ?, status = 'COMPLETED', attempt_count = attempt_count + 1,
                    started_at_ms = ?, finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND status = 'FAILED'
                """, resultJson, startedAtMs, finishedAtMs, analysisRecordId);
    }

    public int failAgain(
            long analysisRecordId, long startedAtMs, long finishedAtMs, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE analysis_record
                SET result_json = NULL, status = 'FAILED', attempt_count = attempt_count + 1,
                    started_at_ms = ?, finished_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'FAILED'
                """, startedAtMs, finishedAtMs, errorMessage, analysisRecordId);
    }

    public int recoverInterrupted(
            MediaMetadataAnalysisDefinition definition, long finishedAtMs, String errorMessage) {
        Objects.requireNonNull(definition, "definition");
        return jdbcTemplate.update("""
                UPDATE analysis_record
                SET result_json = NULL, status = 'FAILED', finished_at_ms = ?, error_message = ?
                WHERE analysis_type = ?
                  AND analyzer_id = ?
                  AND analyzer_version = ?
                  AND configuration_version = ?
                  AND configuration_hash = ?
                  AND configuration_json = ?
                  AND status IN ('PENDING', 'RUNNING')
                """, finishedAtMs, errorMessage,
                MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(), definition.analyzerVersion(),
                definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson());
    }

    public void insert(ContentHash contentHash) {
        jdbcTemplate.update("""
                INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex)
                VALUES (?, ?, ?)
                """, contentHash.analysisRecordId(), contentHash.algorithm(), contentHash.digestHex());
    }

    public Optional<ContentHash> findContentHash(long analysisRecordId) {
        return jdbcTemplate.query("SELECT * FROM content_hash WHERE analysis_record_id = ?",
                (resultSet, rowNumber) -> new ContentHash(
                        resultSet.getLong("analysis_record_id"),
                        resultSet.getString("algorithm"),
                        resultSet.getString("digest_hex")),
                analysisRecordId).stream().findFirst();
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        return Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }
}
