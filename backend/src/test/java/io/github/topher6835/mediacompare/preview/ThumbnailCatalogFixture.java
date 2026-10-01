package io.github.topher6835.mediacompare.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.location.*;
import org.springframework.jdbc.core.JdbcTemplate;

record ThumbnailCatalogFixture(long entryId, long sourceId, String contextId, Path original) {
    static ThumbnailCatalogFixture create(Path directory, JdbcTemplate jdbc, CatalogRepository catalog, byte[] bytes)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                io.github.topher6835.mediacompare.filesystem.HostFileSystems.isMacOs(),
                "This fixture binds real original paths using APFS evidence; NTFS has separate acceptance coverage");
        Path root = Files.createTempDirectory(directory, "original-").toRealPath();
        Path original = Files.write(root.resolve("candidate.bin"), bytes);
        Source source = catalog.insert(new Source(null, "thumbnail", root.toString(), root.toString(), 0, 1, 1));
        LocationPath location = LocationPathParser.parse(LocationDialect.UNIX, root.toString());
        String contextId = UUID.randomUUID().toString();
        String volumeId = "11111111-2222-3333-4444-555555555555";
        var evidence = new MacOsApfsLocationContextEvidence(1, MacOsApfsLocationContextEvidence.PROFILE, 1,
                location, LocationKeyCodec.encode(location), "apfs", volumeId, "2", true, false, 1,
                MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, contextId, 1, evidence));
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 1, ?, 1, 1)
                """, contextId, new LocationPathCodec().encode(location), LocationKeyCodec.encode(location).value(), acceptance);
        bind(jdbc, source.id(), contextId, location, volumeId);
        var attributes = Files.readAttributes(original, BasicFileAttributes.class);
        var modified = attributes.lastModifiedTime().toInstant();
        ContentRecord content = catalog.insert(new ContentRecord(null, attributes.size(), 1));
        LocationPath fileLocation = location.append("candidate.bin");
        FileEntry entry = catalog.insert(new FileEntry(null, "RESOLVED", contextId,
                new LocationPathCodec().encode(fileLocation), LocationKeyCodec.encode(fileLocation).value(),
                content.id(), attributes.size(), modified.getEpochSecond(), modified.getNano(), "bin", 0, 1, 1));
        membership(jdbc, source.id(), entry.id());
        return new ThumbnailCatalogFixture(entry.id(), source.id(), contextId, original);
    }

    void overlap(JdbcTemplate jdbc, CatalogRepository catalog) {
        Path root = original.getParent();
        Source source = catalog.insert(new Source(null, "overlap", root.toString(), root.toString(), 0, 1, 1));
        LocationPath location = LocationPathParser.parse(LocationDialect.UNIX, root.toString());
        bind(jdbc, source.id(), contextId, location, "11111111-2222-3333-4444-555555555555");
        membership(jdbc, source.id(), entryId);
    }

    private static void bind(JdbcTemplate jdbc, long sourceId, String contextId, LocationPath location, String volumeId) {
        var evidence = new MacOsApfsSourceRootEvidence(1, MacOsApfsSourceRootEvidence.PROFILE, 1, contextId, 1, 1,
                location, LocationKeyCodec.encode(location), volumeId, "10",
                new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(new SourceBindingEvidence(1, sourceId, evidence));
        jdbc.update("""
                UPDATE source SET root_path_key = ?, root_path_dialect = 'unix', bound_location_context_id = ?,
                    binding_evidence_json = ?, location_revision = 1 WHERE id = ?
                """, LocationKeyCodec.encode(location).value(), contextId, binding, sourceId);
    }

    private static void membership(JdbcTemplate jdbc, long sourceId, long entryId) {
        jdbc.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path, path_key,
                    applicability_status, presence_status, observed_file_entry_revision, first_seen_at_ms,
                    last_seen_at_ms, observed_source_location_revision, observed_location_context_revision)
                VALUES (?, ?, 'candidate.bin', 'candidate.bin', 'ACTIVE', 'PRESENT', 0, 1, 1, 1, 1)
                """, sourceId, entryId);
    }
}
