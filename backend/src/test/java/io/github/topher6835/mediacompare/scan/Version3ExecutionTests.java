package io.github.topher6835.mediacompare.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.analysis.Version2ContentHashingService;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector.MountObservation;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;
import io.github.topher6835.mediacompare.matching.ExactDuplicateService;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;

class Version3ExecutionTests {
    private static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String VOLUME_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void boundSourceCompletesFourStagesAndPublishesResolvedContentAndHash() throws Exception {
        Path directory = Files.createTempDirectory("media-v3-execution-").toRealPath();
        Path sourceRoot = Files.createDirectory(directory.resolve("source"));
        Files.writeString(sourceRoot.resolve("photo.jpg"), "trusted file bytes");
        String url = "jdbc:sqlite:" + directory.resolve("catalog.db") + "?foreign_keys=on";
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--logging.level.root=ERROR")) {
            assertEquals(url, app.getEnvironment().getProperty("spring.datasource.url"));
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            LocationPath location = path(sourceRoot);
            seed(jdbc, location, sourceRoot);
            var admission = execution(app, sourceRoot);
            assertEquals(3, admission.create(10).job().executionVersion());
            var completed = admission.run(10);
            assertEquals("COMPLETED", completed.job().status());
            assertEquals(List.of("DISCOVERY", "RECONCILIATION", "CONTENT_ASSIGNMENT",
                    "CONTENT_HASHING"), completed.stages().stream().map(stage -> stage.stageType()).toList());
            assertEquals(4, completed.stages().stream()
                    .filter(stage -> "COMPLETED".equals(stage.status())).count());
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM file_entry WHERE location_identity_status='RESOLVED'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM source_membership WHERE presence_status='PRESENT'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM content_record"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM content_hash"));
        }
    }

    @Test
    void nestedSourcesSharePhysicalFilesAcrossBothOrdersAndRepeatScans() throws Exception {
        for (boolean parentFirst : List.of(true, false)) {
            Path directory = Files.createTempDirectory("media-v3-overlap-").toRealPath();
            Path parentRoot = Files.createDirectory(directory.resolve("root"));
            Path childRoot = Files.createDirectory(parentRoot.resolve("child"));
            Path parentOnly = Files.writeString(parentRoot.resolve("parent-only.jpg"), "same bytes");
            Path shared = Files.writeString(childRoot.resolve("shared.jpg"), "same bytes");
            Path secondShared = Files.writeString(childRoot.resolve("second-shared.jpg"), "other bytes");
            String url = "jdbc:sqlite:" + directory.resolve("catalog.db") + "?foreign_keys=on";
            try (ConfigurableApplicationContext app = new SpringApplicationBuilder(MediaCompareApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.datasource.url=" + url, "--logging.level.root=ERROR")) {
                JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
                long parentId = parentFirst ? 1 : 2;
                long childId = parentFirst ? 2 : 1;
                seedOverlapping(jdbc, parentRoot, childRoot, parentId, childId);
                var execution = execution(app, parentRoot);

                scan(jdbc, 10, parentId, childId);
                assertEquals("COMPLETED", run(execution, 10));

                long parentOnlyFileId = fileId(jdbc, parentOnly);
                long sharedFileId = fileId(jdbc, shared);
                long secondSharedFileId = fileId(jdbc, secondShared);
                assertNotEquals(parentOnlyFileId, sharedFileId);
                assertNotEquals(sharedFileId, secondSharedFileId);
                assertEquals(3, count(jdbc, "SELECT COUNT(*) FROM file_entry WHERE location_identity_status='RESOLVED'"));
                assertEquals(5, count(jdbc, "SELECT COUNT(*) FROM source_membership"));
                long parentOnlyMembership = membershipId(jdbc, parentId, parentOnlyFileId,
                        "parent-only.jpg", "PRESENT");
                long parentSharedMembership = membershipId(jdbc, parentId, sharedFileId,
                        "child/shared.jpg", "PRESENT");
                long childSharedMembership = membershipId(jdbc, childId, sharedFileId,
                        "shared.jpg", "PRESENT");
                long parentSecondMembership = membershipId(jdbc, parentId, secondSharedFileId,
                        "child/second-shared.jpg", "PRESENT");
                long childSecondMembership = membershipId(jdbc, childId, secondSharedFileId,
                        "second-shared.jpg", "PRESENT");
                assertNotEquals(parentSharedMembership, childSharedMembership);
                assertNotEquals(parentSecondMembership, childSecondMembership);
                assertEquals(1, membershipCount(jdbc, parentOnlyFileId));
                assertEquals(2, membershipCount(jdbc, sharedFileId));
                assertEquals(2, membershipCount(jdbc, secondSharedFileId));
                Long parentOnlyContent = contentId(jdbc, parentOnlyFileId);
                Long sharedContent = contentId(jdbc, sharedFileId);
                Long secondContent = contentId(jdbc, secondSharedFileId);
                assertNotNull(parentOnlyContent);
                assertNotNull(sharedContent);
                assertNotNull(secondContent);
                long sharedRevision = fileRevision(jdbc, sharedFileId);
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest("same bytes".getBytes(StandardCharsets.UTF_8)));
                var duplicates = app.getBean(ExactDuplicateService.class).findGroup(digest).orElseThrow();
                assertEquals(2, duplicates.summary().presentOccurrenceCount());
                assertEquals(2, duplicates.summary().sourceCount());
                assertEquals("same bytes".length(), duplicates.summary().potentialStorageSavingsBytes());
                assertEquals(3, duplicates.occurrences().size());

                scan(jdbc, 20, parentId, childId);
                assertEquals("COMPLETED", run(execution, 20));
                assertEquals(3, count(jdbc, "SELECT COUNT(*) FROM file_entry WHERE location_identity_status='RESOLVED'"));
                assertEquals(5, count(jdbc, "SELECT COUNT(*) FROM source_membership"));
                assertEquals(parentOnlyFileId, fileId(jdbc, parentOnly));
                assertEquals(sharedFileId, fileId(jdbc, shared));
                assertEquals(secondSharedFileId, fileId(jdbc, secondShared));
                assertEquals(parentOnlyContent, contentId(jdbc, parentOnlyFileId));
                assertEquals(sharedContent, contentId(jdbc, sharedFileId));
                assertEquals(secondContent, contentId(jdbc, secondSharedFileId));
                assertEquals(sharedRevision, fileRevision(jdbc, sharedFileId));
                assertEquals(parentOnlyMembership, membershipId(jdbc, parentId, parentOnlyFileId,
                        "parent-only.jpg", "PRESENT"));
                assertEquals(parentSharedMembership, membershipId(jdbc, parentId, sharedFileId,
                        "child/shared.jpg", "PRESENT"));
                assertEquals(childSharedMembership, membershipId(jdbc, childId, sharedFileId,
                        "shared.jpg", "PRESENT"));
                assertEquals(parentSecondMembership, membershipId(jdbc, parentId, secondSharedFileId,
                        "child/second-shared.jpg", "PRESENT"));
                assertEquals(childSecondMembership, membershipId(jdbc, childId, secondSharedFileId,
                        "second-shared.jpg", "PRESENT"));

                Files.delete(shared);
                scan(jdbc, 30, parentId);
                assertEquals("COMPLETED", run(execution, 30));
                assertEquals(parentSharedMembership, membershipId(jdbc, parentId, sharedFileId,
                        "child/shared.jpg", "MISSING"));
                assertEquals(childSharedMembership, membershipId(jdbc, childId, sharedFileId,
                        "shared.jpg", "PRESENT"));
                assertEquals(sharedFileId, fileId(jdbc, shared));
            }
        }
    }

    private static void seedOverlapping(JdbcTemplate jdbc, Path parentRoot, Path childRoot,
            long parentId, long childId) {
        LocationPath anchor = path(parentRoot);
        var context = new MacOsApfsLocationContextEvidence(1,
                MacOsApfsLocationContextEvidence.PROFILE, 1,
                anchor, LocationKeyCodec.encode(anchor), "apfs", VOLUME_ID, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, CONTEXT_ID, 3, context));
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 3, ?, 1, 2)
                """, CONTEXT_ID, new LocationPathCodec().encode(anchor),
                LocationKeyCodec.encode(anchor).value(), acceptance);
        insertBoundSource(jdbc, parentId, "Parent", parentRoot);
        insertBoundSource(jdbc, childId, "Child", childRoot);
    }

    private static void insertBoundSource(JdbcTemplate jdbc, long id, String name, Path hostRoot) {
        LocationPath root = path(hostRoot);
        var evidence = new MacOsApfsSourceRootEvidence(1,
                MacOsApfsSourceRootEvidence.PROFILE, 1,
                CONTEXT_ID, 3, 3, root, LocationKeyCodec.encode(root), VOLUME_ID,
                "10", new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(new SourceBindingEvidence(1, id, evidence));
        jdbc.update("""
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    root_path_dialect, bound_location_context_id, binding_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, 3, 'unix', ?, ?, 1, 2)
                """, id, name, hostRoot.toString(), LocationKeyCodec.encode(root).value(), CONTEXT_ID, binding);
    }

    private static void scan(JdbcTemplate jdbc, long runId, long... sourceIds) {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (?, 'INDEX', 'PENDING', 1, '{}', 1)
                """, runId);
        for (long sourceId : sourceIds) {
            jdbc.update("""
                    INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                        source_location_revision, traversal_generation)
                    VALUES (?, ?, ?, 'PENDING', 3, 0)
                    """, runId * 10 + sourceId, runId, sourceId);
        }
    }

    private static String run(Version3ScanExecutionService execution, long runId) {
        execution.create(runId);
        return execution.run(runId).job().status();
    }

    private static long fileId(JdbcTemplate jdbc, Path file) {
        return jdbc.queryForObject("""
                SELECT id FROM file_entry WHERE location_context_id = ? AND location_key = ?
                """, Long.class, CONTEXT_ID, LocationKeyCodec.encode(path(file)).value());
    }

    private static Long contentId(JdbcTemplate jdbc, long fileId) {
        return jdbc.queryForObject("SELECT current_content_id FROM file_entry WHERE id = ?", Long.class, fileId);
    }

    private static int membershipCount(JdbcTemplate jdbc, long fileId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM source_membership WHERE file_entry_id = ?",
                Integer.class, fileId);
    }

    private static long fileRevision(JdbcTemplate jdbc, long fileId) {
        return jdbc.queryForObject("SELECT observation_revision FROM file_entry WHERE id = ?",
                Long.class, fileId);
    }

    private static long membershipId(JdbcTemplate jdbc, long sourceId, long fileId,
            String relativePath, String presence) {
        return jdbc.queryForObject("""
                SELECT id FROM source_membership WHERE source_id = ? AND file_entry_id = ?
                  AND relative_path = ? AND presence_status = ? AND applicability_status = 'ACTIVE'
                """, Long.class, sourceId, fileId, relativePath, presence);
    }

    private static Version3ScanExecutionService execution(ConfigurableApplicationContext app, Path mountRoot) {
        CatalogRepository catalog = app.getBean(CatalogRepository.class);
        LocationContextRepository contexts = app.getBean(LocationContextRepository.class);
        Version3AuthorityCapture capture = new Version3AuthorityCapture(catalog, contexts) {
                @Override
                public io.github.topher6835.mediacompare.scan.authority.ScanAuthorityResult<
                        io.github.topher6835.mediacompare.scan.authority.ScanAuthoritySnapshot>
                        capture(long sourceId) {
                    var source = catalog.findSourceById(sourceId).orElseThrow();
                    var context = contexts.findById(source.boundLocationContextId()).orElseThrow();
                    var baseline = LocationContextAcceptanceAuthority.requireCurrentAccepted(context)
                            .macOsApfsEvidence();
                    var root = SourceBindingAuthority.requireCurrentBound(source)
                            .macOsApfsSourceRootEvidence();
                    var freshContext = new MacOsApfsLocationContextEvidence(1,
                            MacOsApfsLocationContextEvidence.PROFILE, 1,
                            baseline.anchorLocationPath(), baseline.anchorLocationKey(),
                            baseline.fileSystemType(), baseline.volumeUuid(), baseline.anchorInode(),
                            true, false, 2, baseline.diagnostics());
                    var freshRoot = new MacOsApfsSourceRootEvidence(1,
                            MacOsApfsSourceRootEvidence.PROFILE, 1,
                            root.locationContextId(), root.locationContextRevision(),
                            root.sourceLocationRevision(), root.rootLocationPath(), root.rootLocationKey(),
                            root.volumeUuid(), root.rootInode(), root.rootBirthTime(), true, false, 2);
                    return ScanObservationAuthority.capture(source, context,
                            ContinuityProbeResult.accepted(freshContext),
                            ContinuityProbeResult.accepted(freshRoot));
                }
            };
        var mount = new MountObservation("/dev/disk2s1", mountRoot.toString(), "apfs", VOLUME_ID);
        var walker = new Version3DiscoveryWalker(ignored -> ContinuityProbeResult.accepted(mount));
        var discovery = new Version3DiscoveryService(
                app.getBean(ScanRepository.class), app.getBean(JobRepository.class), catalog,
                capture, walker, app.getBean(DiscoveryBatchWriter.class),
                app.getBean(DiscoveryExecutionState.class),
                app.getBean(Version3DiscoveryCompletionWriter.class));
        return new Version3ScanExecutionService(
                app.getBean(ScanRepository.class), app.getBean(JobRepository.class),
                catalog, contexts, discovery, app.getBean(Version3ReconciliationService.class),
                app.getBean(Version2ContentAssignmentService.class),
                app.getBean(Version2ContentHashingService.class));
    }

    private static void seed(JdbcTemplate jdbc, LocationPath root, Path hostRoot) {
        var context = new MacOsApfsLocationContextEvidence(1,
                MacOsApfsLocationContextEvidence.PROFILE, 1,
                root, LocationKeyCodec.encode(root), "apfs", VOLUME_ID, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, CONTEXT_ID, 3, context));
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 3, ?, 1, 2)
                """, CONTEXT_ID, new LocationPathCodec().encode(root),
                LocationKeyCodec.encode(root).value(), acceptance);
        var rootEvidence = new MacOsApfsSourceRootEvidence(1,
                MacOsApfsSourceRootEvidence.PROFILE, 1,
                CONTEXT_ID, 3, 3, root, LocationKeyCodec.encode(root), VOLUME_ID,
                "10", new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(
                new SourceBindingEvidence(1, 1, rootEvidence));
        jdbc.update("""
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    root_path_dialect, bound_location_context_id, binding_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (1, 'Bound', ?, ?, 3, 'unix', ?, ?, 1, 2)
                """, hostRoot.toString(), LocationKeyCodec.encode(root).value(), CONTEXT_ID, binding);
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (10, 'INDEX', 'PENDING', 1, '{}', 1)
                """);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation)
                VALUES (11, 10, 1, 'PENDING', 3, 0)
                """);
    }

    private static LocationPath path(Path value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value.toString());
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
