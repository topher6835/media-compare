package io.github.topher6835.mediacompare.matching;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExactDuplicateRepository {

    private static final String COMPLETED_STATUS = "COMPLETED";

    private static final String EXACT_MEMBERS_CTE = """
            WITH exact_members AS (
                SELECT content_hash.digest_hex,
                       analysis_record.content_record_id,
                       content_record.size_bytes
                FROM content_hash
                JOIN analysis_record ON analysis_record.id = content_hash.analysis_record_id
                JOIN content_record ON content_record.id = analysis_record.content_record_id
                WHERE analysis_record.status = ?
                  AND analysis_record.analysis_type = ?
                  AND analysis_record.analyzer_id = ?
                  AND analysis_record.analyzer_version = ?
                  AND analysis_record.configuration_version = ?
                  AND analysis_record.configuration_hash = ?
                  AND analysis_record.configuration_json = ?
                  AND content_hash.algorithm = ?
                  AND length(content_hash.digest_hex) = 64
                  AND content_hash.digest_hex NOT GLOB '*[^0-9a-f]*'
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public ExactDuplicateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OptionalLong findFirstInvalidCompletedExactArtifactId() {
        List<Long> ids = jdbcTemplate.query("""
                SELECT analysis_record.id
                FROM analysis_record
                LEFT JOIN content_hash ON content_hash.analysis_record_id = analysis_record.id
                WHERE analysis_record.status = ?
                  AND analysis_record.analysis_type = ?
                  AND analysis_record.analyzer_id = ?
                  AND analysis_record.analyzer_version = ?
                  AND analysis_record.configuration_version = ?
                  AND analysis_record.configuration_hash = ?
                  AND (
                      analysis_record.configuration_json <> ?
                      OR content_hash.analysis_record_id IS NULL
                      OR content_hash.algorithm <> ?
                      OR length(content_hash.digest_hex) <> 64
                      OR content_hash.digest_hex GLOB '*[^0-9a-f]*'
                  )
                ORDER BY analysis_record.id
                LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getLong("id"), exactDefinitionParameters());
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    public Optional<String> findFirstSizeMismatchedDigest() {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                SELECT digest_hex
                FROM exact_members
                GROUP BY digest_hex
                HAVING COUNT(DISTINCT content_record_id) >= 2
                   AND MIN(size_bytes) <> MAX(size_bytes)
                ORDER BY digest_hex
                LIMIT 1
                """, (resultSet, rowNumber) -> resultSet.getString("digest_hex"),
                exactDefinitionParameters()).stream().findFirst();
    }

    public List<ExactDuplicateGroupCounts> findGroupCounts(
            String afterDigestHex, int limit) {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                , selected_groups AS (
                    SELECT digest_hex,
                           MIN(size_bytes) AS size_bytes,
                           COUNT(DISTINCT content_record_id) AS content_record_count
                    FROM exact_members
                    WHERE digest_hex > ?
                    GROUP BY digest_hex
                    HAVING COUNT(DISTINCT content_record_id) >= 2
                    ORDER BY digest_hex
                    LIMIT ?
                )
                SELECT selected_groups.digest_hex,
                       selected_groups.size_bytes,
                       selected_groups.content_record_count,
                       COALESCE(SUM(CASE WHEN file_entry.presence_status = 'PRESENT' THEN 1 ELSE 0 END), 0)
                           AS present_occurrence_count,
                       COALESCE(SUM(CASE WHEN file_entry.presence_status = 'MISSING' THEN 1 ELSE 0 END), 0)
                           AS missing_occurrence_count,
                       COUNT(DISTINCT file_entry.source_id) AS source_count
                FROM selected_groups
                JOIN exact_members ON exact_members.digest_hex = selected_groups.digest_hex
                LEFT JOIN file_entry ON file_entry.current_content_id = exact_members.content_record_id
                GROUP BY selected_groups.digest_hex,
                         selected_groups.size_bytes,
                         selected_groups.content_record_count
                ORDER BY selected_groups.digest_hex
                """, (resultSet, rowNumber) -> mapGroupCounts(resultSet),
                exactDefinitionParameters(afterDigestHex, limit));
    }

    public Optional<ExactDuplicateGroupCounts> findGroupCounts(String digestHex) {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                , selected_group AS (
                    SELECT digest_hex,
                           MIN(size_bytes) AS size_bytes,
                           COUNT(DISTINCT content_record_id) AS content_record_count
                    FROM exact_members
                    WHERE digest_hex = ?
                    GROUP BY digest_hex
                    HAVING COUNT(DISTINCT content_record_id) >= 2
                )
                SELECT selected_group.digest_hex,
                       selected_group.size_bytes,
                       selected_group.content_record_count,
                       COALESCE(SUM(CASE WHEN file_entry.presence_status = 'PRESENT' THEN 1 ELSE 0 END), 0)
                           AS present_occurrence_count,
                       COALESCE(SUM(CASE WHEN file_entry.presence_status = 'MISSING' THEN 1 ELSE 0 END), 0)
                           AS missing_occurrence_count,
                       COUNT(DISTINCT file_entry.source_id) AS source_count
                FROM selected_group
                JOIN exact_members ON exact_members.digest_hex = selected_group.digest_hex
                LEFT JOIN file_entry ON file_entry.current_content_id = exact_members.content_record_id
                GROUP BY selected_group.digest_hex,
                         selected_group.size_bytes,
                         selected_group.content_record_count
                """, (resultSet, rowNumber) -> mapGroupCounts(resultSet),
                exactDefinitionParameters(digestHex)).stream().findFirst();
    }

    public List<ExactDuplicateMember> findMembers(String digestHex) {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                SELECT content_record_id, size_bytes
                FROM exact_members
                WHERE digest_hex = ?
                ORDER BY content_record_id
                """, (resultSet, rowNumber) -> new ExactDuplicateMember(
                        resultSet.getLong("content_record_id"),
                        resultSet.getLong("size_bytes")),
                exactDefinitionParameters(digestHex));
    }

    public List<ExactDuplicateOccurrence> findOccurrences(String digestHex) {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                SELECT file_entry.id AS file_entry_id,
                       file_entry.current_content_id AS content_record_id,
                       file_entry.source_id,
                       source.name AS source_name,
                       file_entry.relative_path,
                       file_entry.presence_status
                FROM exact_members
                JOIN file_entry ON file_entry.current_content_id = exact_members.content_record_id
                JOIN source ON source.id = file_entry.source_id
                WHERE exact_members.digest_hex = ?
                ORDER BY file_entry.current_content_id, file_entry.source_id,
                         file_entry.relative_path, file_entry.id
                """, (resultSet, rowNumber) -> new ExactDuplicateOccurrence(
                        resultSet.getLong("file_entry_id"),
                        resultSet.getLong("content_record_id"),
                        resultSet.getLong("source_id"),
                        resultSet.getString("source_name"),
                        resultSet.getString("relative_path"),
                        resultSet.getString("presence_status")),
                exactDefinitionParameters(digestHex));
    }

    private static ExactDuplicateGroupCounts mapGroupCounts(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new ExactDuplicateGroupCounts(
                resultSet.getString("digest_hex"),
                resultSet.getLong("size_bytes"),
                resultSet.getLong("content_record_count"),
                resultSet.getLong("present_occurrence_count"),
                resultSet.getLong("missing_occurrence_count"),
                resultSet.getLong("source_count"));
    }

    private static Object[] exactDefinitionParameters(Object... additionalParameters) {
        Object[] parameters = new Object[8 + additionalParameters.length];
        parameters[0] = COMPLETED_STATUS;
        parameters[1] = Sha256AnalysisDefinition.ANALYSIS_TYPE;
        parameters[2] = Sha256AnalysisDefinition.ANALYZER_ID;
        parameters[3] = Sha256AnalysisDefinition.ANALYZER_VERSION;
        parameters[4] = Sha256AnalysisDefinition.CONFIGURATION_VERSION;
        parameters[5] = Sha256AnalysisDefinition.CONFIGURATION_HASH;
        parameters[6] = Sha256AnalysisDefinition.CONFIGURATION_JSON;
        parameters[7] = Sha256AnalysisDefinition.ALGORITHM;
        System.arraycopy(additionalParameters, 0, parameters, 8, additionalParameters.length);
        return parameters;
    }
}
