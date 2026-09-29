package io.github.topher6835.mediacompare.library;

import java.nio.file.Path;
import java.util.UUID;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.location.*;
import io.github.topher6835.mediacompare.preview.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database-only fixtures deliberately require no real originals or cache files. */
public final class MediaLibraryTestFixtures {
    final JdbcTemplate jdbc;
    final CatalogRepository catalog;
    final AnalysisRepository analysis;
    final MediaMetadataResultCodec codec;
    final String contextId = UUID.randomUUID().toString();
    final LocationPath anchor = LocationPathParser.parse(LocationDialect.UNIX, "/images/" + contextId);
    final Source primary;

    MediaLibraryTestFixtures(JdbcTemplate jdbc, CatalogRepository catalog, AnalysisRepository analysis,
            MediaMetadataResultCodec codec) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.analysis = analysis;
        this.codec = codec;
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key, lifecycle_status,
                    continuity_status, revision, continuity_evidence_json, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 1, '{}', 1, 1)
                """, contextId, new LocationPathCodec().encode(anchor), LocationKeyCodec.encode(anchor).value());
        primary = source("primary");
    }

    Source source(String name) {
        String root = Path.of(System.getProperty("java.io.tmpdir"), contextId).toAbsolutePath().toString();
        return catalog.insert(new Source(null, name, root, root, 1, "unix", contextId, "{}", 1, 1));
    }

    FileEntry image(String format, String relativePath) {
        var content = catalog.insert(new ContentRecord(null, 42, 1));
        var entry = occurrence(content.id(), relativePath);
        imageMetadata(analysis, codec, content.id(), format);
        return entry;
    }

    FileEntry occurrence(long contentId, String relativePath) {
        var location = anchor;
        for (String component : relativePath.split("/")) location = location.append(component);
        var entry = catalog.insert(new FileEntry(null, "RESOLVED", contextId,
                new LocationPathCodec().encode(location), LocationKeyCodec.encode(location).value(), contentId,
                42, 1700000000L, 123456789, FileExtensionNormalizer.fromRelativePath(relativePath), 3, 1, 1));
        membership(primary, entry, relativePath);
        return entry;
    }

    void hash(long contentId, String digest) {
        var record = analysis.insert(new AnalysisRecord(null, contentId, Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID, Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION, Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON, null, "COMPLETED", 1, 1, 1L, 2L, null));
        analysis.insert(new ContentHash(record.id(), Sha256AnalysisDefinition.ALGORITHM, digest));
    }

    public static void imageMetadata(AnalysisRepository analysis, MediaMetadataResultCodec codec,
            long contentId, String format) {
        var definition = ImageIoMediaMetadataDefinition.definition();
        var result = new AvailableMediaMetadata(1, MediaKind.IMAGE, new ImageMediaMetadata(format, 600, 400), null);
        analysis.insert(new AnalysisRecord(null, contentId, "MEDIA_METADATA", definition.analyzerId(),
                definition.analyzerVersion(), definition.configurationVersion(), definition.configurationHash(),
                definition.configurationJson(), codec.write(result), "COMPLETED", 1, 1, 1L, 2L, null));
    }

    void membership(Source source, FileEntry entry, String path) {
        jdbc.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path, path_key,
                    applicability_status, presence_status, observed_file_entry_revision, first_seen_at_ms,
                    last_seen_at_ms, observed_source_location_revision, observed_location_context_revision)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'PRESENT', 3, 1, 1, 1, 1)
                """, source.id(), entry.id(), path, path);
    }

    PreviewAsset thumbnail(FileEntry entry, PreviewDefinition definition) {
        var evidence = PreviewSourceEvidence.from(entry);
        String key = PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL, definition);
        return new PreviewAsset(null, key, evidence, PreviewKind.SMALL_THUMBNAIL, definition,
                PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png"), "image/png", 120, 80, 6, 1);
    }

    static void clear(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM source_membership");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM analysis_record");
        jdbc.update("DELETE FROM content_record");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }
}
