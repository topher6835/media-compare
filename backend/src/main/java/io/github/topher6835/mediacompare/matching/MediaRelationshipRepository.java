package io.github.topher6835.mediacompare.matching;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MediaRelationshipRepository {
    private final JdbcTemplate jdbcTemplate;

    public MediaRelationshipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MediaRelationship insert(MediaRelationship relationship) {
        Objects.requireNonNull(relationship, "relationship");
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO media_relationship (
                        content_record_a_id, content_record_b_id, relationship_type, direction,
                        confidence, evidence_json, matcher_id, matcher_version,
                        configuration_version, configuration_hash, configuration_json, created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, relationship.contentRecordAId());
            statement.setLong(2, relationship.contentRecordBId());
            statement.setString(3, relationship.relationshipType().name());
            statement.setString(4, relationship.direction().name());
            if (relationship.confidence() == null) {
                statement.setNull(5, Types.REAL);
            } else {
                statement.setDouble(5, relationship.confidence());
            }
            statement.setString(6, relationship.evidenceJson());
            statement.setString(7, relationship.matcherId());
            statement.setString(8, relationship.matcherVersion());
            statement.setLong(9, relationship.configurationVersion());
            statement.setString(10, relationship.configurationHash());
            statement.setString(11, relationship.configurationJson());
            statement.setLong(12, relationship.createdAtMs());
            return statement;
        }, keyHolder);

        long id = Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
        return new MediaRelationship(id, relationship.contentRecordAId(), relationship.contentRecordBId(),
                relationship.relationshipType(), relationship.direction(), relationship.confidence(),
                relationship.evidenceJson(), relationship.matcherId(), relationship.matcherVersion(),
                relationship.configurationVersion(), relationship.configurationHash(),
                relationship.configurationJson(), relationship.createdAtMs());
    }

    public Optional<MediaRelationship> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM media_relationship WHERE id = ?",
                (resultSet, rowNumber) -> mapRelationship(resultSet), id).stream().findFirst();
    }

    /**
     * Returns retained history for the requested types in ID order, including every matcher/configuration.
     * Must not be used directly for current grouping; use {@link #findBySelection} instead.
     */
    public List<MediaRelationship> findByTypes(Set<MediaRelationshipType> enabledTypes) {
        Objects.requireNonNull(enabledTypes, "enabledTypes");
        if (enabledTypes.isEmpty()) {
            return List.of();
        }
        List<String> types = enabledTypes.stream().map(MediaRelationshipType::name).sorted().toList();
        String placeholders = String.join(", ", Collections.nCopies(types.size(), "?"));
        return jdbcTemplate.query("""
                SELECT * FROM media_relationship
                WHERE relationship_type IN (%s)
                ORDER BY id
                """.formatted(placeholders),
                (resultSet, rowNumber) -> mapRelationship(resultSet), types.toArray());
    }

    /** Reads only exact explicitly accepted definitions in one query, in ascending relationship ID order. */
    public List<MediaRelationship> findBySelection(MediaRelationshipSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (selection.definitions().isEmpty()) {
            return List.of();
        }
        List<String> predicates = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (MediaRelationshipDefinition definition : selection.definitions()) {
            predicates.add("""
                    (relationship_type = ? AND matcher_id = ? AND matcher_version = ?
                     AND configuration_version = ? AND configuration_hash = ? AND configuration_json = ?)
                    """);
            parameters.add(definition.relationshipType().name());
            parameters.add(definition.matcherId());
            parameters.add(definition.matcherVersion());
            parameters.add(definition.configurationVersion());
            parameters.add(definition.configurationHash());
            parameters.add(definition.configurationJson());
        }
        return jdbcTemplate.query("SELECT * FROM media_relationship WHERE "
                + String.join(" OR ", predicates) + " ORDER BY id",
                (resultSet, rowNumber) -> mapRelationship(resultSet), parameters.toArray());
    }

    private static MediaRelationship mapRelationship(ResultSet resultSet) throws SQLException {
        double score = resultSet.getDouble("confidence");
        Double confidence = resultSet.wasNull() ? null : score;
        return new MediaRelationship(resultSet.getLong("id"),
                resultSet.getLong("content_record_a_id"), resultSet.getLong("content_record_b_id"),
                MediaRelationshipType.valueOf(resultSet.getString("relationship_type")),
                MediaRelationshipDirection.valueOf(resultSet.getString("direction")), confidence,
                resultSet.getString("evidence_json"), resultSet.getString("matcher_id"),
                resultSet.getString("matcher_version"), resultSet.getLong("configuration_version"),
                resultSet.getString("configuration_hash"), resultSet.getString("configuration_json"),
                resultSet.getLong("created_at_ms"));
    }
}
