package io.github.topher6835.mediacompare.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

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
            ), physical_entries AS (
                SELECT file_entry.id, file_entry.current_content_id, file_entry.extension_key,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM source_membership AS membership
                           WHERE membership.file_entry_id = file_entry.id
                             AND membership.applicability_status = 'ACTIVE'
                             AND membership.presence_status = 'PRESENT'
                       ) THEN 'PRESENT' ELSE 'MISSING' END AS presence_status
                FROM file_entry
                WHERE EXISTS (
                    SELECT 1 FROM source_membership AS membership
                    WHERE membership.file_entry_id = file_entry.id
                      AND membership.applicability_status = 'ACTIVE'
                )
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
            String afterDigestHex, int limit, ExactDuplicateFilter filter) {
        String matchingGroups = filter.active()
                ? """
                        , matching_groups AS (
                            SELECT eligible_groups.*
                            FROM eligible_groups
                            WHERE EXISTS (
                                SELECT 1
                                FROM exact_members AS matching_member
                                JOIN physical_entries AS matching_entry
                                  ON matching_entry.current_content_id = matching_member.content_record_id
                                WHERE matching_member.digest_hex = eligible_groups.digest_hex
                                  AND matching_entry.extension_key IN (%s)
                            )
                        )
                        """.formatted(placeholders(filter.effectiveExtensionKeys().size()))
                : """
                        , matching_groups AS (
                            SELECT * FROM eligible_groups
                        )
                        """;
        String sql = EXACT_MEMBERS_CTE + """
                , eligible_groups AS (
                    SELECT digest_hex,
                           MIN(size_bytes) AS size_bytes,
                           COUNT(DISTINCT content_record_id) AS content_record_count
                    FROM exact_members
                    GROUP BY digest_hex
                    HAVING COUNT(DISTINCT content_record_id) >= 2
                )
                """ + matchingGroups + """
                , selected_groups AS (
                    SELECT *
                    FROM matching_groups
                    WHERE digest_hex > ?
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
                       (SELECT COUNT(DISTINCT membership.source_id)
                        FROM exact_members AS source_member
                        JOIN file_entry AS source_entry
                          ON source_entry.current_content_id = source_member.content_record_id
                        JOIN source_membership AS membership
                          ON membership.file_entry_id = source_entry.id
                        WHERE source_member.digest_hex = selected_groups.digest_hex
                          AND membership.applicability_status = 'ACTIVE') AS source_count
                FROM selected_groups
                JOIN exact_members ON exact_members.digest_hex = selected_groups.digest_hex
                LEFT JOIN physical_entries AS file_entry
                  ON file_entry.current_content_id = exact_members.content_record_id
                GROUP BY selected_groups.digest_hex,
                         selected_groups.size_bytes,
                         selected_groups.content_record_count
                ORDER BY selected_groups.digest_hex
                """;

        List<Object> parameters = new ArrayList<>(List.of(exactDefinitionParameters()));
        parameters.addAll(sorted(filter.effectiveExtensionKeys()));
        parameters.add(afterDigestHex);
        parameters.add(limit);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> mapGroupCounts(resultSet), parameters.toArray());
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
                       (SELECT COUNT(DISTINCT membership.source_id)
                        FROM exact_members AS source_member
                        JOIN file_entry AS source_entry
                          ON source_entry.current_content_id = source_member.content_record_id
                        JOIN source_membership AS membership
                          ON membership.file_entry_id = source_entry.id
                        WHERE source_member.digest_hex = selected_group.digest_hex
                          AND membership.applicability_status = 'ACTIVE') AS source_count
                FROM selected_group
                JOIN exact_members ON exact_members.digest_hex = selected_group.digest_hex
                LEFT JOIN physical_entries AS file_entry
                  ON file_entry.current_content_id = exact_members.content_record_id
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

    public List<ExactDuplicateOccurrenceRow> findOccurrences(String digestHex) {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                SELECT file_entry.id AS file_entry_id,
                       file_entry.current_content_id AS content_record_id,
                       membership.id AS membership_id,
                       membership.source_id,
                       source.name AS source_name,
                       membership.relative_path,
                       file_entry.extension_key,
                       membership.presence_status,
                       membership.applicability_status
                FROM exact_members
                JOIN file_entry ON file_entry.current_content_id = exact_members.content_record_id
                JOIN source_membership AS membership ON membership.file_entry_id = file_entry.id
                JOIN source ON source.id = membership.source_id
                WHERE exact_members.digest_hex = ?
                  AND membership.applicability_status = 'ACTIVE'
                ORDER BY file_entry.current_content_id, membership.source_id,
                         membership.relative_path, file_entry.id, membership.id
                """, (resultSet, rowNumber) -> new ExactDuplicateOccurrenceRow(
                        resultSet.getLong("file_entry_id"),
                        resultSet.getLong("content_record_id"),
                        resultSet.getLong("membership_id"),
                        resultSet.getLong("source_id"),
                        resultSet.getString("source_name"),
                        resultSet.getString("relative_path"),
                        resultSet.getString("extension_key"),
                        resultSet.getString("presence_status"),
                        resultSet.getString("applicability_status")),
                exactDefinitionParameters(digestHex));
    }

    public List<ExactDuplicateFilterMatchCount> findFilterMatchCounts(
            List<String> digestHexes, Set<String> extensionKeys) {
        if (digestHexes.isEmpty() || extensionKeys.isEmpty()) {
            return List.of();
        }
        String sql = EXACT_MEMBERS_CTE + """
                SELECT exact_members.digest_hex,
                       file_entry.extension_key,
                       COUNT(*) AS occurrence_count
                FROM exact_members
                JOIN physical_entries AS file_entry
                  ON file_entry.current_content_id = exact_members.content_record_id
                WHERE exact_members.digest_hex IN (%s)
                  AND file_entry.extension_key IN (%s)
                GROUP BY exact_members.digest_hex, file_entry.extension_key
                ORDER BY exact_members.digest_hex, file_entry.extension_key
                """.formatted(placeholders(digestHexes.size()), placeholders(extensionKeys.size()));

        List<Object> parameters = new ArrayList<>(List.of(exactDefinitionParameters()));
        parameters.addAll(digestHexes);
        parameters.addAll(sorted(extensionKeys));
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new ExactDuplicateFilterMatchCount(
                resultSet.getString("digest_hex"),
                resultSet.getString("extension_key"),
                resultSet.getLong("occurrence_count")), parameters.toArray());
    }

    public List<ExactDuplicateFilterOption> findFilterOptions() {
        return jdbcTemplate.query(EXACT_MEMBERS_CTE + """
                , duplicate_groups AS (
                    SELECT digest_hex
                    FROM exact_members
                    GROUP BY digest_hex
                    HAVING COUNT(DISTINCT content_record_id) >= 2
                )
                SELECT file_entry.extension_key,
                       COUNT(DISTINCT exact_members.digest_hex) AS exact_duplicate_group_count,
                       COUNT(*) AS retained_occurrence_count
                FROM duplicate_groups
                JOIN exact_members ON exact_members.digest_hex = duplicate_groups.digest_hex
                JOIN physical_entries AS file_entry
                  ON file_entry.current_content_id = exact_members.content_record_id
                WHERE file_entry.extension_key IS NOT NULL
                GROUP BY file_entry.extension_key
                ORDER BY file_entry.extension_key
                """, (resultSet, rowNumber) -> {
                    String extensionKey = resultSet.getString("extension_key");
                    return new ExactDuplicateFilterOption(
                            extensionKey,
                            io.github.topher6835.mediacompare.catalog.FileCategory
                                    .fromExtensionKey(extensionKey).orElse(null),
                            resultSet.getLong("exact_duplicate_group_count"),
                            resultSet.getLong("retained_occurrence_count"));
                }, exactDefinitionParameters());
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

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
