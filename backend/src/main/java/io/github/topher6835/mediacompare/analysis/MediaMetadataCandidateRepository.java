package io.github.topher6835.mediacompare.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MediaMetadataCandidateRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaMetadataCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MediaMetadataContentCandidate> findCandidates(
            MediaMetadataAnalysisDefinition definition, long afterContentRecordId, int limit) {
        Objects.requireNonNull(definition, "definition");
        requirePositiveLimit(limit);

        return jdbcTemplate.query("""
                SELECT content_record.id, content_record.size_bytes
                FROM content_record
                WHERE content_record.id > ?
                  AND EXISTS (
                      SELECT 1
                      FROM file_entry
                      WHERE file_entry.current_content_id = content_record.id
                        AND file_entry.presence_status = 'PRESENT'
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM analysis_record
                      WHERE analysis_record.content_record_id = content_record.id
                        AND analysis_record.analysis_type = ?
                        AND analysis_record.analyzer_id = ?
                        AND analysis_record.analyzer_version = ?
                        AND analysis_record.configuration_version = ?
                        AND analysis_record.configuration_hash = ?
                  )
                ORDER BY content_record.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new MediaMetadataContentCandidate(
                        resultSet.getLong("id"), resultSet.getLong("size_bytes")),
                afterContentRecordId,
                MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(),
                definition.analyzerVersion(),
                definition.configurationVersion(),
                definition.configurationHash(),
                limit);
    }

    public List<MediaMetadataFileCandidate> findOccurrences(
            MediaMetadataContentCandidate candidate, long afterFileEntryId, int limit) {
        Objects.requireNonNull(candidate, "candidate");
        requirePositiveLimit(limit);

        return jdbcTemplate.query("""
                SELECT file_entry.id AS file_entry_id,
                       file_entry.current_content_id,
                       content_record.size_bytes AS content_size_bytes,
                       file_entry.source_id,
                       source.root_path,
                       source.location_revision,
                       file_entry.relative_path,
                       file_entry.observation_revision,
                       file_entry.size_bytes,
                       file_entry.modified_time_epoch_second,
                       file_entry.modified_time_nano
                FROM file_entry
                JOIN source ON source.id = file_entry.source_id
                JOIN content_record ON content_record.id = file_entry.current_content_id
                WHERE file_entry.current_content_id = ?
                  AND content_record.size_bytes = ?
                  AND file_entry.presence_status = 'PRESENT'
                  AND file_entry.id > ?
                ORDER BY file_entry.id
                LIMIT ?
                """, MediaMetadataCandidateRepository::mapFileCandidate,
                candidate.contentRecordId(), candidate.expectedContentSizeBytes(),
                afterFileEntryId, limit);
    }

    public int verifyCandidate(MediaMetadataFileCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return jdbcTemplate.update("""
                UPDATE file_entry
                SET current_content_id = current_content_id
                WHERE id = ?
                  AND source_id = ?
                  AND presence_status = 'PRESENT'
                  AND current_content_id = ?
                  AND observation_revision = ?
                  AND size_bytes = ?
                  AND modified_time_epoch_second IS ?
                  AND modified_time_nano IS ?
                  AND EXISTS (
                      SELECT 1
                      FROM source
                      WHERE source.id = ?
                        AND source.root_path = ?
                        AND source.location_revision = ?
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM content_record
                      WHERE content_record.id = ?
                        AND content_record.size_bytes = ?
                  )
                """,
                candidate.fileEntryId(),
                candidate.sourceId(),
                candidate.contentRecordId(),
                candidate.observationRevision(),
                candidate.expectedSizeBytes(),
                candidate.expectedModifiedTimeEpochSecond(),
                candidate.expectedModifiedTimeNano(),
                candidate.sourceId(),
                candidate.sourceRootPath(),
                candidate.sourceLocationRevision(),
                candidate.contentRecordId(),
                candidate.expectedContentSizeBytes());
    }

    private static MediaMetadataFileCandidate mapFileCandidate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new MediaMetadataFileCandidate(
                resultSet.getLong("current_content_id"),
                resultSet.getLong("content_size_bytes"),
                resultSet.getLong("file_entry_id"),
                resultSet.getLong("source_id"),
                resultSet.getString("root_path"),
                resultSet.getLong("location_revision"),
                resultSet.getString("relative_path"),
                resultSet.getLong("observation_revision"),
                resultSet.getLong("size_bytes"),
                nullableLong(resultSet, "modified_time_epoch_second"),
                nullableInteger(resultSet, "modified_time_nano"));
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
