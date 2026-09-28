package io.github.topher6835.mediacompare.preview;

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
public class PreviewAssetRepository {
    private final JdbcTemplate jdbc;

    public PreviewAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Caller must publish the usable final file first. Duplicate publication fails uniqueness. */
    public PreviewAsset insert(PreviewAsset asset) {
        Objects.requireNonNull(asset, "asset");
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO preview_asset (
                        asset_key, file_entry_id, content_record_id, file_observation_revision,
                        source_size_bytes, source_modified_time_epoch_second, source_modified_time_nano,
                        preview_kind, generator_id, generator_version, configuration_version,
                        configuration_hash, configuration_json, relative_path, media_type,
                        pixel_width, pixel_height, asset_size_bytes, created_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            PreviewSourceEvidence evidence = asset.evidence();
            PreviewDefinition definition = asset.definition();
            statement.setString(1, asset.assetKey());
            statement.setLong(2, evidence.fileEntryId());
            statement.setLong(3, evidence.contentRecordId());
            statement.setLong(4, evidence.observationRevision());
            statement.setLong(5, evidence.sizeBytes());
            statement.setLong(6, evidence.modifiedTimeEpochSecond());
            statement.setInt(7, evidence.modifiedTimeNano());
            statement.setString(8, asset.kind().name());
            statement.setString(9, definition.generatorId());
            statement.setString(10, definition.generatorVersion());
            statement.setLong(11, definition.configurationVersion());
            statement.setString(12, definition.configurationHash());
            statement.setString(13, definition.configurationJson());
            statement.setString(14, asset.relativePath());
            statement.setString(15, asset.mediaType());
            statement.setInt(16, asset.pixelWidth());
            statement.setInt(17, asset.pixelHeight());
            statement.setLong(18, asset.assetSizeBytes());
            statement.setLong(19, asset.createdAtMs());
            return statement;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey(), "Database did not return a generated key").longValue();
        return new PreviewAsset(id, asset.assetKey(), asset.evidence(), asset.kind(), asset.definition(),
                asset.relativePath(), asset.mediaType(), asset.pixelWidth(), asset.pixelHeight(),
                asset.assetSizeBytes(), asset.createdAtMs());
    }

    public Optional<PreviewAsset> findByAssetKey(String assetKey) {
        PreviewAssetKey.requireValid(assetKey);
        return jdbc.query("SELECT * FROM preview_asset WHERE asset_key = ?",
                PreviewAssetRepository::mapAsset, assetKey).stream().findFirst();
    }

    /** Exact catalog evidence match; no Source membership identity or stale-row deletion. */
    public Optional<PreviewAsset> findCurrent(long fileEntryId, PreviewDefinition definition, PreviewKind kind) {
        if (fileEntryId <= 0) {
            throw new IllegalArgumentException("fileEntryId must be positive");
        }
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(kind, "kind");
        return jdbc.query("""
                SELECT asset.* FROM preview_asset AS asset
                JOIN file_entry AS entry ON entry.id = asset.file_entry_id
                  AND entry.current_content_id = asset.content_record_id
                  AND entry.observation_revision = asset.file_observation_revision
                  AND entry.size_bytes = asset.source_size_bytes
                  AND entry.modified_time_epoch_second = asset.source_modified_time_epoch_second
                  AND entry.modified_time_nano = asset.source_modified_time_nano
                WHERE asset.file_entry_id = ? AND asset.preview_kind = ?
                  AND asset.generator_id = ? AND asset.generator_version = ?
                  AND asset.configuration_version = ? AND asset.configuration_hash = ?
                """, PreviewAssetRepository::mapAsset, fileEntryId, kind.name(),
                definition.generatorId(), definition.generatorVersion(),
                definition.configurationVersion(), definition.configurationHash()).stream().findFirst();
    }

    private static PreviewAsset mapAsset(ResultSet row, int rowNumber) throws SQLException {
        return new PreviewAsset(row.getLong("id"), row.getString("asset_key"),
                new PreviewSourceEvidence(row.getLong("file_entry_id"), row.getLong("content_record_id"),
                        row.getLong("file_observation_revision"), row.getLong("source_size_bytes"),
                        row.getLong("source_modified_time_epoch_second"), row.getInt("source_modified_time_nano")),
                PreviewKind.valueOf(row.getString("preview_kind")),
                new PreviewDefinition(row.getString("generator_id"), row.getString("generator_version"),
                        row.getLong("configuration_version"), row.getString("configuration_hash"),
                        row.getString("configuration_json")),
                row.getString("relative_path"), row.getString("media_type"),
                row.getInt("pixel_width"), row.getInt("pixel_height"),
                row.getLong("asset_size_bytes"), row.getLong("created_at_ms"));
    }
}
