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
                      JOIN source_membership AS membership
                        ON membership.file_entry_id = file_entry.id
                      JOIN source ON source.id = membership.source_id
                      JOIN location_context AS context ON context.id = file_entry.location_context_id
                      WHERE file_entry.current_content_id = content_record.id
                        AND file_entry.location_identity_status = 'RESOLVED'
                        AND membership.applicability_status = 'ACTIVE'
                        AND membership.presence_status = 'PRESENT'
                        AND membership.observed_file_entry_revision = file_entry.observation_revision
                        AND membership.observed_source_location_revision = source.location_revision
                        AND membership.observed_location_context_revision = context.revision
                        AND source.bound_location_context_id = context.id
                        AND context.lifecycle_status = 'ACTIVE'
                        AND context.continuity_status = 'ACCEPTED'
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
                        AND analysis_record.status IN ('COMPLETED', 'PENDING', 'RUNNING')
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
                       membership.id AS membership_id,
                       membership.source_id,
                       source.location_revision,
                       file_entry.location_context_id,
                       context.revision AS context_revision,
                       membership.membership_revision,
                       file_entry.location_path,
                       file_entry.location_key,
                       file_entry.observation_revision,
                       file_entry.size_bytes,
                       file_entry.modified_time_epoch_second,
                       file_entry.modified_time_nano
                FROM file_entry
                JOIN source_membership AS membership ON membership.id = (
                    SELECT MIN(eligible.id) FROM source_membership AS eligible
                    JOIN source AS eligible_source ON eligible_source.id = eligible.source_id
                    JOIN location_context AS eligible_context
                      ON eligible_context.id = file_entry.location_context_id
                    WHERE eligible.file_entry_id = file_entry.id
                      AND eligible.applicability_status = 'ACTIVE'
                      AND eligible.presence_status = 'PRESENT'
                      AND eligible.observed_file_entry_revision = file_entry.observation_revision
                      AND eligible.observed_source_location_revision = eligible_source.location_revision
                      AND eligible.observed_location_context_revision = eligible_context.revision
                      AND eligible_source.bound_location_context_id = eligible_context.id
                      AND eligible_context.lifecycle_status = 'ACTIVE'
                      AND eligible_context.continuity_status = 'ACCEPTED'
                )
                JOIN source ON source.id = membership.source_id
                JOIN location_context AS context ON context.id = file_entry.location_context_id
                JOIN content_record ON content_record.id = file_entry.current_content_id
                WHERE file_entry.current_content_id = ?
                  AND content_record.size_bytes = ?
                  AND file_entry.location_identity_status = 'RESOLVED'
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
                  AND location_identity_status = 'RESOLVED'
                  AND location_context_id = ?
                  AND location_path = ? AND location_key = ?
                  AND current_content_id = ?
                  AND observation_revision = ?
                  AND size_bytes = ?
                  AND modified_time_epoch_second IS ?
                  AND modified_time_nano IS ?
                  AND EXISTS (
                      SELECT 1 FROM source_membership AS membership
                      JOIN source ON source.id = membership.source_id
                      JOIN location_context AS context ON context.id = file_entry.location_context_id
                      WHERE membership.id = ? AND membership.file_entry_id = file_entry.id
                        AND membership.source_id = ?
                        AND membership.applicability_status = 'ACTIVE'
                        AND membership.presence_status = 'PRESENT'
                        AND membership.membership_revision = ?
                        AND membership.observed_file_entry_revision = file_entry.observation_revision
                        AND membership.observed_source_location_revision = ?
                        AND membership.observed_location_context_revision = ?
                        AND source.location_revision = membership.observed_source_location_revision
                        AND source.bound_location_context_id = context.id
                        AND context.revision = membership.observed_location_context_revision
                        AND context.lifecycle_status = 'ACTIVE'
                        AND context.continuity_status = 'ACCEPTED'
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM content_record
                      WHERE content_record.id = ?
                        AND content_record.size_bytes = ?
                  )
                """,
                candidate.fileEntryId(),
                candidate.contextId(), candidate.locationPath(), candidate.locationKey(),
                candidate.contentRecordId(),
                candidate.observationRevision(),
                candidate.expectedSizeBytes(),
                candidate.expectedModifiedTimeEpochSecond(),
                candidate.expectedModifiedTimeNano(),
                candidate.membershipId(), candidate.sourceId(), candidate.membershipRevision(),
                candidate.sourceLocationRevision(), candidate.contextRevision(),
                candidate.contentRecordId(),
                candidate.expectedContentSizeBytes());
    }

    private static MediaMetadataFileCandidate mapFileCandidate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new MediaMetadataFileCandidate(
                resultSet.getLong("current_content_id"),
                resultSet.getLong("content_size_bytes"),
                resultSet.getLong("file_entry_id"),
                resultSet.getLong("membership_id"),
                resultSet.getLong("source_id"),
                resultSet.getLong("location_revision"),
                resultSet.getString("location_context_id"),
                resultSet.getLong("context_revision"),
                resultSet.getLong("membership_revision"),
                resultSet.getString("location_path"),
                resultSet.getString("location_key"),
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
