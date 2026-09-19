package io.github.topher6835.mediacompare.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LocationContextRepository {

    private final JdbcTemplate jdbcTemplate;

    public LocationContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LocationContext insert(LocationContext context) {
        jdbcTemplate.update("""
                INSERT INTO location_context (
                    id, anchor_location_path, anchor_location_key, lifecycle_status,
                    continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                context.id(),
                context.anchorLocationPath(),
                context.anchorLocationKey(),
                context.lifecycleStatus().name(),
                context.continuityStatus().name(),
                context.revision(),
                context.continuityEvidenceJson(),
                context.createdAtMs(),
                context.updatedAtMs());
        return context;
    }

    public Optional<LocationContext> findById(String id) {
        return jdbcTemplate.query("SELECT * FROM location_context WHERE id = ?", LocationContextRepository::map, id)
                .stream()
                .findFirst();
    }

    public Optional<LocationContext> findActiveByAnchorLocationKey(String anchorLocationKey) {
        return jdbcTemplate.query("""
                SELECT *
                FROM location_context
                WHERE anchor_location_key = ? AND lifecycle_status = 'ACTIVE'
                """, LocationContextRepository::map, anchorLocationKey)
                .stream()
                .findFirst();
    }

    private static LocationContext map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LocationContext(
                resultSet.getString("id"),
                resultSet.getString("anchor_location_path"),
                resultSet.getString("anchor_location_key"),
                LocationContext.LifecycleStatus.valueOf(resultSet.getString("lifecycle_status")),
                LocationContext.ContinuityStatus.valueOf(resultSet.getString("continuity_status")),
                resultSet.getLong("revision"),
                resultSet.getString("continuity_evidence_json"),
                resultSet.getLong("created_at_ms"),
                resultSet.getLong("updated_at_ms"));
    }
}
