package io.github.topher6835.mediacompare.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
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
            var admission = app.getBean(Version3ScanExecutionService.class);
            assertEquals(3, admission.create(10).job().executionVersion());
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
            var mount = new MountObservation("/dev/disk2s1", sourceRoot.toString(), "apfs", VOLUME_ID);
            var walker = new Version3DiscoveryWalker(
                    ignored -> ContinuityProbeResult.accepted(mount));
            var discovery = new Version3DiscoveryService(
                    app.getBean(ScanRepository.class), app.getBean(JobRepository.class), catalog,
                    capture, walker, app.getBean(DiscoveryBatchWriter.class),
                    app.getBean(DiscoveryExecutionState.class),
                    app.getBean(Version3DiscoveryCompletionWriter.class));
            var execution = new Version3ScanExecutionService(
                    app.getBean(ScanRepository.class), app.getBean(JobRepository.class),
                    catalog, contexts, discovery, app.getBean(Version3ReconciliationService.class),
                    app.getBean(Version2ContentAssignmentService.class),
                    app.getBean(Version2ContentHashingService.class));
            var completed = execution.run(10);
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
