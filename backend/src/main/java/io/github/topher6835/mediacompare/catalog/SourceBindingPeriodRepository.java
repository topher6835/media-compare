package io.github.topher6835.mediacompare.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Persists binding history; current binding authority remains on Source. */
@Repository
public class SourceBindingPeriodRepository {
    private final JdbcTemplate jdbcTemplate;

    public SourceBindingPeriodRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SourceBindingPeriod insertOpen(SourceBindingPeriod period) {
        if (period.unboundSourceLocationRevision() != null || period.unboundAtMs() != null) {
            throw new IllegalArgumentException("An open binding period cannot have closing fields");
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO source_binding_period (
                        source_id, bound_source_location_revision, location_context_id,
                        root_path_dialect, root_path, root_path_key, binding_evidence_json,
                        bound_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, period.sourceId());
            statement.setLong(2, period.boundSourceLocationRevision());
            statement.setString(3, period.locationContextId());
            statement.setString(4, period.rootPathDialect());
            statement.setString(5, period.rootPath());
            statement.setString(6, period.rootPathKey());
            statement.setString(7, period.bindingEvidenceJson());
            statement.setLong(8, period.boundAtMs());
            return statement;
        }, keyHolder);
        return new SourceBindingPeriod(keyHolder.getKey().longValue(), period.sourceId(),
                period.boundSourceLocationRevision(), period.locationContextId(),
                period.rootPathDialect(), period.rootPath(), period.rootPathKey(),
                period.bindingEvidenceJson(), period.boundAtMs(), null, null);
    }

    public Optional<SourceBindingPeriod> findOpenBySourceId(long sourceId) {
        return jdbcTemplate.query("""
                SELECT * FROM source_binding_period
                WHERE source_id = ? AND unbound_source_location_revision IS NULL
                """, SourceBindingPeriodRepository::map, sourceId).stream().findFirst();
    }

    public int closeOpen(SourceBindingPeriod period, long unboundRevision, long unboundAtMs) {
        return jdbcTemplate.update("""
                UPDATE source_binding_period
                SET unbound_source_location_revision = ?, unbound_at_ms = ?
                WHERE id = ? AND source_id = ? AND bound_source_location_revision = ?
                  AND unbound_source_location_revision IS NULL AND unbound_at_ms IS NULL
                  AND location_context_id = ? AND root_path_dialect = ?
                  AND root_path = ? AND root_path_key = ? AND binding_evidence_json = ?
                  AND bound_at_ms = ?
                """, unboundRevision, unboundAtMs, period.id(), period.sourceId(),
                period.boundSourceLocationRevision(), period.locationContextId(),
                period.rootPathDialect(), period.rootPath(), period.rootPathKey(),
                period.bindingEvidenceJson(), period.boundAtMs());
    }

    public List<SourceBindingPeriod> findBySourceId(long sourceId) {
        return jdbcTemplate.query("""
                SELECT * FROM source_binding_period WHERE source_id = ?
                ORDER BY bound_source_location_revision, id
                """, SourceBindingPeriodRepository::map, sourceId);
    }

    private static SourceBindingPeriod map(ResultSet resultSet, int rowNumber) throws SQLException {
        long closingRevision = resultSet.getLong("unbound_source_location_revision");
        Long nullableClosingRevision = resultSet.wasNull() ? null : closingRevision;
        long closingTime = resultSet.getLong("unbound_at_ms");
        Long nullableClosingTime = resultSet.wasNull() ? null : closingTime;
        return new SourceBindingPeriod(resultSet.getLong("id"), resultSet.getLong("source_id"),
                resultSet.getLong("bound_source_location_revision"),
                resultSet.getString("location_context_id"), resultSet.getString("root_path_dialect"),
                resultSet.getString("root_path"), resultSet.getString("root_path_key"),
                resultSet.getString("binding_evidence_json"), resultSet.getLong("bound_at_ms"),
                nullableClosingRevision, nullableClosingTime);
    }
}
