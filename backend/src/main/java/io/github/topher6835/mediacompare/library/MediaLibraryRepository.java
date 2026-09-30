package io.github.topher6835.mediacompare.library;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import io.github.topher6835.mediacompare.analysis.AvailableMediaMetadata;
import io.github.topher6835.mediacompare.analysis.ImageIoMediaMetadataDefinition;
import io.github.topher6835.mediacompare.analysis.MediaKind;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.catalog.FileCategory;
import io.github.topher6835.mediacompare.preview.PreviewAssetKey;
import io.github.topher6835.mediacompare.preview.PreviewKind;
import io.github.topher6835.mediacompare.preview.PreviewSourceEvidence;
import io.github.topher6835.mediacompare.preview.SmallThumbnailDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MediaLibraryRepository {
    private static final String PHOTO_EXTENSIONS = FileCategory.PHOTO.extensionKeys().stream()
            .sorted().map(key -> "'" + key + "'").collect(Collectors.joining(", "));
    // Correlated route lookups use the existing membership FileEntry index, not a full-library aggregate.
    private static final String TRUSTED_ROUTE = """
            eligible.file_entry_id = entry.id
              AND eligible.applicability_status = 'ACTIVE' AND eligible.presence_status = 'PRESENT'
              AND eligible.observed_file_entry_revision = entry.observation_revision
              AND eligible.observed_source_location_revision = eligible_source.location_revision
              AND eligible.observed_location_context_revision = context.revision
              AND eligible_source.bound_location_context_id = context.id
            """;

    static final String PAGE_SQL = """
            SELECT entry.id AS file_entry_id, entry.current_content_id, entry.extension_key,
                   entry.size_bytes, entry.observation_revision,
                   entry.modified_time_epoch_second, entry.modified_time_nano,
                   source.id AS source_id, source.name AS source_name, membership.relative_path,
                   analysis.status AS analysis_status, analysis.result_json,
                   asset.asset_key, asset.pixel_width, asset.pixel_height,
                   (SELECT COUNT(DISTINCT eligible.source_id) FROM source_membership AS eligible
                    JOIN source AS eligible_source ON eligible_source.id = eligible.source_id
                    WHERE %s) AS source_count
            FROM file_entry AS entry
            JOIN content_record AS content ON content.id = entry.current_content_id
            JOIN location_context AS context ON context.id = entry.location_context_id
              AND context.lifecycle_status = 'ACTIVE' AND context.continuity_status = 'ACCEPTED'
            JOIN source_membership AS membership ON membership.id = (
                SELECT MIN(eligible.id) FROM source_membership AS eligible
                JOIN source AS eligible_source ON eligible_source.id = eligible.source_id
                WHERE %s
            )
            JOIN source ON source.id = membership.source_id
            LEFT JOIN analysis_record AS analysis ON analysis.content_record_id = content.id
              AND analysis.analysis_type = ? AND analysis.analyzer_id = ? AND analysis.analyzer_version = ?
              AND analysis.configuration_version = ? AND analysis.configuration_hash = ?
            LEFT JOIN preview_asset AS asset ON asset.file_entry_id = entry.id
              AND asset.content_record_id = entry.current_content_id
              AND asset.file_observation_revision = entry.observation_revision
              AND asset.source_size_bytes = entry.size_bytes
              AND asset.source_modified_time_epoch_second = entry.modified_time_epoch_second
              AND asset.source_modified_time_nano = entry.modified_time_nano
              AND asset.preview_kind = ? AND asset.generator_id = ? AND asset.generator_version = ?
              AND asset.configuration_version = ? AND asset.configuration_hash = ?
            WHERE entry.id > ? AND entry.location_identity_status = 'RESOLVED'
              AND (entry.extension_key IN (%s) OR
                   (analysis.status = 'COMPLETED' AND
                    (NOT json_valid(analysis.result_json) OR
                     (json_extract(analysis.result_json, '$.outcome') = 'AVAILABLE' AND
                      json_extract(analysis.result_json, '$.mediaKind') = 'IMAGE'))))
            ORDER BY entry.id
            LIMIT ?
            """.formatted(TRUSTED_ROUTE, TRUSTED_ROUTE, PHOTO_EXTENSIONS);

    private final JdbcTemplate jdbc;
    private final MediaMetadataResultCodec codec;

    public MediaLibraryRepository(JdbcTemplate jdbc, MediaMetadataResultCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public List<MediaLibraryItem> findPage(long afterFileEntryId, int limit) {
        return jdbc.query(PAGE_SQL, this::mapItem, pageParameters(afterFileEntryId, limit));
    }

    static Object[] pageParameters(long afterFileEntryId, int limit) {
        var metadata = ImageIoMediaMetadataDefinition.definition();
        var thumbnail = SmallThumbnailDefinition.definition();
        return new Object[] {MediaMetadataAnalysisDefinition.ANALYSIS_TYPE, metadata.analyzerId(),
                metadata.analyzerVersion(), metadata.configurationVersion(), metadata.configurationHash(),
                PreviewKind.SMALL_THUMBNAIL.name(), thumbnail.generatorId(), thumbnail.generatorVersion(),
                thumbnail.configurationVersion(), thumbnail.configurationHash(), afterFileEntryId, limit};
    }

    private MediaLibraryItem mapItem(ResultSet row, int rowNumber) throws SQLException {
        AvailableMediaMetadata available = null;
        if ("COMPLETED".equals(row.getString("analysis_status"))) {
            // Invalid completed results must still fail closed through the strict codec.
            var decoded = codec.read(row.getString("result_json"));
            if (decoded instanceof AvailableMediaMetadata present) {
                if (present.mediaKind() != MediaKind.IMAGE) {
                    throw new IllegalStateException("Media library query returned incompatible image metadata");
                }
                available = present;
            }
        }
        var image = available == null ? null : available.image();
        String relativePath = row.getString("relative_path");
        String assetKey = row.getString("asset_key");
        ThumbnailReference reference = ThumbnailReference.missing();
        if (assetKey != null) {
            var evidence = new PreviewSourceEvidence(row.getLong("file_entry_id"), row.getLong("current_content_id"),
                    row.getLong("observation_revision"), row.getLong("size_bytes"),
                    row.getLong("modified_time_epoch_second"), row.getInt("modified_time_nano"));
            if (!assetKey.equals(PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL,
                    SmallThumbnailDefinition.definition()))) {
                throw new IllegalStateException("Published thumbnail key disagrees with current evidence");
            }
            reference = ThumbnailReference.published(assetKey, row.getInt("pixel_width"), row.getInt("pixel_height"));
        }
        var support = image != null && (image.format().equals("jpeg") || image.format().equals("png"))
                ? MediaLibraryItem.GenerationSupport.SUPPORTED : MediaLibraryItem.GenerationSupport.UNSUPPORTED;
        return new MediaLibraryItem(row.getLong("file_entry_id"), row.getLong("current_content_id"),
                row.getLong("source_id"), row.getString("source_name"), relativePath,
                relativePath.substring(relativePath.lastIndexOf('/') + 1), row.getString("extension_key"),
                row.getLong("size_bytes"), image == null ? null : image.format(),
                image == null ? null : image.width(), image == null ? null : image.height(),
                row.getLong("source_count"), support, reference);
    }
}
