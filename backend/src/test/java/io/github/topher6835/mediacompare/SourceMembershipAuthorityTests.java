package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentWriter;
import io.github.topher6835.mediacompare.analysis.ContentHashWriter;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.SourceMembershipPublicationService;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import io.github.topher6835.mediacompare.scan.Version3ScanExecutionService;
import io.github.topher6835.mediacompare.scan.authority.ChildStorageBoundary;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;
import io.github.topher6835.mediacompare.matching.ExactDuplicateService;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.Version2InterruptionRecovery;

class SourceMembershipAuthorityTests {
    private static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String VOLUME_ID = "11111111-2222-3333-4444-555555555555";
    private static final LocationPath ANCHOR = path("/Volumes/Archive");
    private static final LocationPath CHILD = path("/Volumes/Archive/Photos");
    private static final LocationPath FILE = path("/Volumes/Archive/Photos/a.jpg");

    @Test
    void overlappingSourcesShareOneResolvedFileInBothOrders() throws Exception {
        for (boolean parentFirst : List.of(true, false)) {
            try (var app = app()) {
                JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
                seed(jdbc);
                var publisher = app.getBean(SourceMembershipPublicationService.class);
                var first = publisher.publish(candidate(parentFirst ? 1 : 2, 12, 123),
                        parentFirst ? 11 : 12, 1, 100);
                var second = publisher.publish(candidate(parentFirst ? 2 : 1, 12, 123),
                        parentFirst ? 12 : 11, 1, 101);
                assertEquals(first.fileEntryId(), second.fileEntryId());
                assertNotEquals(first.id(), second.id());
                assertEquals("Photos/a.jpg", jdbc.queryForObject(
                        "SELECT relative_path FROM source_membership WHERE source_id = 1", String.class));
                assertEquals("a.jpg", jdbc.queryForObject(
                        "SELECT relative_path FROM source_membership WHERE source_id = 2", String.class));
                assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM file_entry WHERE location_identity_status = 'RESOLVED'"));
            }
        }
    }

    @Test
    void concurrentOverlappingPublicationsShareOneResolvedFile() throws Exception {
        try (var app = app(); var workers = Executors.newFixedThreadPool(2)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var start = new CountDownLatch(1);
            var parent = workers.submit(() -> {
                start.await();
                return publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            });
            var child = workers.submit(() -> {
                start.await();
                return publisher.publish(candidate(2, 12, 123), 12, 1, 100);
            });
            start.countDown();
            assertEquals(parent.get(10, TimeUnit.SECONDS).fileEntryId(),
                    child.get(10, TimeUnit.SECONDS).fileEntryId());
            assertEquals(1, count(jdbc,
                    "SELECT COUNT(*) FROM file_entry WHERE location_identity_status = 'RESOLVED'"));
            assertEquals(2, count(jdbc, "SELECT COUNT(*) FROM source_membership"));
        }
    }

    @Test
    void missingAndReappearanceChangeMembershipButPreserveUnchangedContent() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var first = publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (50, 12, 1)");
            jdbc.update("UPDATE file_entry SET current_content_id = 50 WHERE id = ?", first.fileEntryId());
            scanSource(jdbc, 13, 1, "DISCOVERED");
            var runSource = app.getBean(ScanRepository.class).findScanRunSourceById(13).orElseThrow();
            assertEquals(1, publisher.reconcile(new MissingClaimAuthority(
                    1, 3, CONTEXT_ID, 3, ANCHOR), runSource, 200));
            assertEquals("MISSING", app.getBean(SourceMembershipRepository.class)
                    .findMembershipById(first.id()).orElseThrow().presenceStatus());
            scanSource(jdbc, 14, 1, "DISCOVERING");
            var again = publisher.publish(candidate(1, 12, 123), 14, 1, 300);
            assertEquals(first.id(), again.id());
            assertEquals("PRESENT", again.presenceStatus());
            assertEquals(0, app.getBean(SourceMembershipRepository.class)
                    .findById(first.fileEntryId()).orElseThrow().observationRevision());
            assertEquals(50, app.getBean(SourceMembershipRepository.class)
                    .findById(first.fileEntryId()).orElseThrow().currentContentId());
        }
    }

    @Test
    void reconciliationOfParentDoesNotChangeOverlappingChildMembership() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var parent = publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            var child = publisher.publish(candidate(2, 12, 123), 12, 1, 101);
            scanSource(jdbc, 13, 1, "DISCOVERED");
            var row = app.getBean(ScanRepository.class).findScanRunSourceById(13).orElseThrow();
            assertEquals(1, publisher.reconcile(new MissingClaimAuthority(
                    1, 3, CONTEXT_ID, 3, ANCHOR), row, 200));
            var memberships = app.getBean(SourceMembershipRepository.class);
            assertEquals("MISSING", memberships.findMembershipById(parent.id()).orElseThrow().presenceStatus());
            assertEquals("PRESENT", memberships.findMembershipById(child.id()).orElseThrow().presenceStatus());
            assertEquals(parent.fileEntryId(), child.fileEntryId());
        }
    }

    @Test
    void incompleteDiscoveryCannotMakeAMissingClaim() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var membership = publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            var discovering = app.getBean(ScanRepository.class)
                    .findScanRunSourceById(11).orElseThrow();
            assertThrows(IllegalStateException.class, () -> publisher.reconcile(
                    new MissingClaimAuthority(1, 3, CONTEXT_ID, 3, ANCHOR), discovering, 200));
            assertEquals("PRESENT", app.getBean(SourceMembershipRepository.class)
                    .findMembershipById(membership.id()).orElseThrow().presenceStatus());
        }
    }

    @Test
    void byteChangeAdvancesFileRevisionAndClearsContent() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var first = publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (50, 12, 1)");
            jdbc.update("UPDATE file_entry SET current_content_id = 50 WHERE id = ?", first.fileEntryId());
            var changed = publisher.publish(candidate(1, 13, 123), 11, 1, 200);
            var entry = app.getBean(SourceMembershipRepository.class)
                    .findById(first.fileEntryId()).orElseThrow();
            assertEquals(1, entry.observationRevision());
            assertEquals(null, entry.currentContentId());
            assertEquals(1, changed.observedFileEntryRevision());
        }
    }

    @Test
    void legacyPathCollisionRetiresWithoutRetargetingHistory() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            jdbc.update("""
                    INSERT INTO file_entry (id, location_identity_status, size_bytes,
                        observation_revision, first_seen_at_ms, last_seen_at_ms)
                    VALUES (40, 'UNRESOLVED', 12, 4, 1, 1)
                    """);
            jdbc.update("""
                    INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                        path_key, applicability_status, presence_status, observed_file_entry_revision,
                        first_seen_at_ms, last_seen_at_ms)
                    VALUES (1, 40, 'Photos/a.jpg', 'Photos/a.jpg', 'ACTIVE', 'PRESENT', 4, 1, 1)
                    """);
            var published = app.getBean(SourceMembershipPublicationService.class)
                    .publish(candidate(1, 12, 123), 11, 1, 100);
            assertNotEquals(40, published.fileEntryId());
            assertEquals("RETIRED", jdbc.queryForObject("""
                    SELECT applicability_status FROM source_membership WHERE file_entry_id = 40
                    """, String.class));
            assertEquals("UNRESOLVED", jdbc.queryForObject("""
                    SELECT location_identity_status FROM file_entry WHERE id = 40
                    """, String.class));
            assertEquals(1, count(jdbc, """
                    SELECT COUNT(*) FROM source_membership
                    WHERE source_id = 1 AND path_key = 'Photos/a.jpg' AND applicability_status = 'ACTIVE'
                    """));
        }
    }

    @Test
    void staleSourceAndContextRevisionsRejectPublicationWithoutRows() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            jdbc.update("UPDATE source SET location_revision = 4 WHERE id = 1");
            assertThrows(IllegalStateException.class,
                    () -> publisher.publish(candidate(1, 12, 123), 11, 1, 100));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM file_entry"));
            jdbc.update("UPDATE source SET location_revision = 3 WHERE id = 1");
            jdbc.update("UPDATE location_context SET revision = 4 WHERE id = ?", CONTEXT_ID);
            assertThrows(IllegalStateException.class,
                    () -> publisher.publish(candidate(1, 12, 123), 11, 1, 100));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM file_entry"));
        }
    }

    @Test
    void scanRunSourceRevisionMustMatchTrustedCandidateBeforePublication() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            jdbc.update("UPDATE scan_run_source SET source_location_revision = 2 WHERE id = 11");
            var publisher = app.getBean(SourceMembershipPublicationService.class);

            assertThrows(IllegalStateException.class,
                    () -> publisher.publish(candidate(1, 12, 123), 11, 1, 100));

            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM file_entry"));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM source_membership"));
        }
    }

    @Test
    void v3AdmissionRequiresCurrentSupportedBindingForEverySource() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            pendingScan(jdbc, 80, 1);
            var execution = app.getBean(Version3ScanExecutionService.class).create(80);
            assertEquals(3, execution.job().executionVersion());
            assertEquals("DISCOVERY", execution.stages().getFirst().stageType());
        }
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            jdbc.update("""
                    INSERT INTO source (id, name, root_path, root_path_key,
                        location_revision, created_at_ms, updated_at_ms)
                    VALUES (3, 'Unbound', '/Volumes/Archive/Other', 'legacy', 3, 1, 2)
                    """);
            pendingScan(jdbc, 80, 1, 3);
            assertThrows(Version2ExecutionConflictException.class,
                    () -> app.getBean(Version3ScanExecutionService.class).create(80));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM job"));
        }
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            jdbc.update("UPDATE source SET root_path_dialect = 'win-drive' WHERE id = 2");
            pendingScan(jdbc, 80, 1, 2);
            assertThrows(Version2ExecutionConflictException.class,
                    () -> app.getBean(Version3ScanExecutionService.class).create(80));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM job"));
        }
    }

    @Test
    void historicalOpenPeriodDoesNotAuthorizeV3AdmissionAfterSourceAuthorityIsWithdrawn() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            jdbc.update("""
                    INSERT INTO source_binding_period (
                        source_id, bound_source_location_revision, location_context_id,
                        root_path_dialect, root_path, root_path_key, binding_evidence_json, bound_at_ms
                    )
                    SELECT id, location_revision, bound_location_context_id,
                           root_path_dialect, root_path, root_path_key, binding_evidence_json, updated_at_ms
                    FROM source WHERE id = 1
                    """);
            jdbc.update("""
                    UPDATE source SET bound_location_context_id = NULL,
                        root_path_dialect = NULL, binding_evidence_json = NULL,
                        location_revision = location_revision + 1 WHERE id = 1
                    """);
            pendingScan(jdbc, 80, 1);

            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM source_binding_period WHERE source_id = 1"));
            assertThrows(Version2ExecutionConflictException.class,
                    () -> app.getBean(Version3ScanExecutionService.class).create(80));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM job"));
        }
    }

    @Test
    void legacyAcceptanceIsIneligibleButMalformedAcceptanceIsIntegrityFailure() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            pendingScan(jdbc, 80, 1);
            var raw = new MacOsApfsLocationContextEvidenceCodec().encode(contextEvidence());
            jdbc.update("UPDATE location_context SET continuity_evidence_json = ? WHERE id = ?",
                    raw, CONTEXT_ID);
            assertThrows(Version2ExecutionConflictException.class,
                    () -> app.getBean(Version3ScanExecutionService.class).create(80));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM job"));
            jdbc.update("UPDATE location_context SET continuity_evidence_json = '{}' WHERE id = ?",
                    CONTEXT_ID);
            assertThrows(IllegalStateException.class,
                    () -> app.getBean(Version3ScanExecutionService.class).create(80));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM job"));
        }
    }

    @Test
    void duplicateSavingsCountSharedResolvedFileOnce() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var shared = publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            publisher.publish(candidate(2, 12, 123), 12, 1, 101);
            var otherPath = path("/Volumes/Archive/b.jpg");
            var separate = publisher.publish(new ResolvedFileCandidate(1, 3, CONTEXT_ID, 3,
                    otherPath, LocationKeyCodec.encode(otherPath), "b.jpg", "b.jpg",
                    "apfs", VOLUME_ID, ChildStorageBoundary.SAME_ACCEPTED_VOLUME,
                    true, false, 12, 123, 456), 11, 1, 102);
            jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (50, 12, 1)");
            jdbc.update("INSERT INTO content_record (id, size_bytes, created_at_ms) VALUES (51, 12, 1)");
            jdbc.update("UPDATE file_entry SET current_content_id = 50 WHERE id = ?", shared.fileEntryId());
            jdbc.update("UPDATE file_entry SET current_content_id = 51 WHERE id = ?", separate.fileEntryId());
            String digest = "a".repeat(64);
            for (long contentId : List.of(50L, 51L)) {
                jdbc.update("""
                        INSERT INTO analysis_record (content_record_id, analysis_type,
                            analyzer_id, analyzer_version, configuration_version,
                            configuration_hash, configuration_json, status, created_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'COMPLETED', 1)
                        """, contentId, Sha256AnalysisDefinition.ANALYSIS_TYPE,
                        Sha256AnalysisDefinition.ANALYZER_ID,
                        Sha256AnalysisDefinition.ANALYZER_VERSION,
                        Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                        Sha256AnalysisDefinition.CONFIGURATION_HASH,
                        Sha256AnalysisDefinition.CONFIGURATION_JSON);
                long analysisId = jdbc.queryForObject("""
                        SELECT id FROM analysis_record WHERE content_record_id = ?
                        """, Long.class, contentId);
                jdbc.update("""
                        INSERT INTO content_hash (analysis_record_id, algorithm, digest_hex)
                        VALUES (?, ?, ?)
                        """, analysisId, Sha256AnalysisDefinition.ALGORITHM, digest);
            }
            var details = app.getBean(ExactDuplicateService.class).findGroup(digest).orElseThrow();
            assertEquals(2, details.summary().presentOccurrenceCount());
            assertEquals(2, details.summary().sourceCount());
            assertEquals(12, details.summary().potentialStorageSavingsBytes());
            assertEquals(3, details.occurrences().size());
        }
    }

    @Test
    void downstreamCandidatesUseTrustedResolvedMembershipOnly() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            var resolved = publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            jdbc.update("""
                    INSERT INTO file_entry (id, location_identity_status, size_bytes,
                        observation_revision, first_seen_at_ms, last_seen_at_ms)
                    VALUES (40, 'UNRESOLVED', 12, 0, 1, 1)
                    """);
            jdbc.update("""
                    INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                        path_key, applicability_status, presence_status, observed_file_entry_revision,
                        first_seen_at_ms, last_seen_at_ms)
                    VALUES (1, 40, 'legacy.jpg', 'legacy.jpg', 'ACTIVE', 'PRESENT', 0, 1, 1)
                    """);
            var catalog = app.getBean(CatalogRepository.class);
            var assignment = catalog.findContentAssignmentCandidates(11, 1, 0, 10);
            assertEquals(1, assignment.size());
            assertEquals(resolved.fileEntryId(), assignment.getFirst().fileEntryId());
            app.getBean(ContentAssignmentWriter.class).assign(assignment.getFirst(), 200);
            var hashing = catalog.findContentHashCandidates(11, 1, 0, 10);
            assertEquals(1, hashing.size());
            assertEquals(resolved.fileEntryId(), hashing.getFirst().fileEntryId());
            var metadata = app.getBean(MediaMetadataCandidateRepository.class);
            var definition = new MediaMetadataAnalysisDefinition("test.metadata", "1", 1, "hash", "{}");
            var contents = metadata.findCandidates(definition, 0, 10);
            assertEquals(1, contents.size());
            assertEquals(resolved.fileEntryId(), metadata.findOccurrences(
                    contents.getFirst(), 0, 10).getFirst().fileEntryId());
            app.getBean(ContentHashWriter.class).publish(hashing.getFirst(), "a".repeat(64), 201, 202);
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM content_hash"));
        }
    }

    @Test
    void staleMembershipAndFileRevisionsBlockDownstreamPublication() throws Exception {
        try (var app = app()) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            seed(jdbc);
            var publisher = app.getBean(SourceMembershipPublicationService.class);
            publisher.publish(candidate(1, 12, 123), 11, 1, 100);
            var catalog = app.getBean(CatalogRepository.class);
            var staleMembership = catalog.findContentAssignmentCandidates(11, 1, 0, 10).getFirst();
            publisher.publish(candidate(1, 12, 123), 11, 1, 101);
            assertThrows(RuntimeException.class,
                    () -> app.getBean(ContentAssignmentWriter.class).assign(staleMembership, 200));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM content_record"));
            var current = catalog.findContentAssignmentCandidates(11, 1, 0, 10).getFirst();
            publisher.publish(candidate(1, 13, 123), 11, 1, 102);
            assertThrows(RuntimeException.class,
                    () -> app.getBean(ContentAssignmentWriter.class).assign(current, 200));
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM content_record"));
        }
    }

    @Test
    void recoveryFinalizesIncompatibleHistoricalAndInterruptedV3Work() throws Exception {
        for (long version : List.of(1L, 2L, 3L)) {
            try (var app = app()) {
                JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
                seed(jdbc);
                pendingScan(jdbc, 80, 1);
                var jobs = app.getBean(JobRepository.class);
                Job job = jobs.insert(new Job(null, 80L, "SCAN", version,
                        "PENDING", "DISCOVERY", 0, null, 0, 1, null, null, null));
                jobs.insert(new JobStage(null, job.id(), "DISCOVERY", null,
                        "PENDING", 0, null, 0, 1, null, null, null));
                app.getBean(Version2InterruptionRecovery.class)
                        .failIfActive(job.id(), Version2InterruptionRecovery.RESTART_MESSAGE);
                assertEquals("FAILED", jobs.findJobById(job.id()).orElseThrow().status());
                assertEquals("FAILED", jdbc.queryForObject(
                        "SELECT status FROM scan_run WHERE id = 80", String.class));
            }
        }
    }

    private static ConfigurableApplicationContext app() throws Exception {
        String url = "jdbc:sqlite:" + Files.createTempDirectory("media-v6-membership-")
                .resolve("catalog.db") + "?foreign_keys=on";
        ConfigurableApplicationContext app = new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url,
                        "--logging.level.root=ERROR");
        if (!url.equals(app.getEnvironment().getProperty("spring.datasource.url"))) {
            app.close();
            throw new IllegalStateException("Test catalog was not isolated");
        }
        return app;
    }

    private static void seed(JdbcTemplate jdbc) {
        MacOsApfsLocationContextEvidence contextEvidence = contextEvidence();
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, CONTEXT_ID, 3, contextEvidence));
        seedRows(jdbc, acceptance);
    }

    private static MacOsApfsLocationContextEvidence contextEvidence() {
        return new MacOsApfsLocationContextEvidence(
                1, MacOsApfsLocationContextEvidence.PROFILE, 1,
                ANCHOR, LocationKeyCodec.encode(ANCHOR), "apfs", VOLUME_ID, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
    }

    private static void seedRows(JdbcTemplate jdbc, String acceptance) {
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 3, ?, 1, 2)
                """, CONTEXT_ID, new LocationPathCodec().encode(ANCHOR),
                LocationKeyCodec.encode(ANCHOR).value(), acceptance);
        source(jdbc, 1, ANCHOR);
        source(jdbc, 2, CHILD);
        scanSource(jdbc, 11, 1, "DISCOVERING");
        scanSource(jdbc, 12, 2, "DISCOVERING");
    }

    private static void pendingScan(JdbcTemplate jdbc, long scanRunId, long... sources) {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (?, 'INDEX', 'PENDING', 1, '{}', 1)
                """, scanRunId);
        for (long sourceId : sources) {
            jdbc.update("""
                    INSERT INTO scan_run_source (scan_run_id, source_id, status,
                        source_location_revision, traversal_generation)
                    VALUES (?, ?, 'PENDING', 3, 0)
                    """, scanRunId, sourceId);
        }
    }

    private static void source(JdbcTemplate jdbc, long id, LocationPath root) {
        MacOsApfsSourceRootEvidence rootEvidence = new MacOsApfsSourceRootEvidence(
                1, MacOsApfsSourceRootEvidence.PROFILE, 1,
                CONTEXT_ID, 3, 3, root, LocationKeyCodec.encode(root), VOLUME_ID,
                "10", new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(
                new SourceBindingEvidence(1, id, rootEvidence));
        String rootText = "/" + String.join("/", root.components());
        jdbc.update("""
                INSERT INTO source (id, name, root_path, root_path_key, location_revision,
                    root_path_dialect, bound_location_context_id, binding_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, 3, 'unix', ?, ?, 1, 2)
                """, id, "Source " + id, rootText,
                LocationKeyCodec.encode(root).value(), CONTEXT_ID, binding);
    }

    private static void scanSource(JdbcTemplate jdbc, long id, long sourceId, String status) {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms, started_at_ms)
                VALUES (?, 'INDEX', 'RUNNING', 1, '{}', 1, 2)
                """, id);
        jdbc.update("""
                INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                    source_location_revision, traversal_generation, started_at_ms)
                VALUES (?, ?, ?, ?, 3, 1, 2)
                """, id, id, sourceId, status);
    }

    private static ResolvedFileCandidate candidate(long sourceId, long size, long seconds) {
        return new ResolvedFileCandidate(sourceId, 3, CONTEXT_ID, 3,
                FILE, LocationKeyCodec.encode(FILE),
                sourceId == 1 ? "Photos/a.jpg" : "a.jpg",
                sourceId == 1 ? "Photos/a.jpg" : "a.jpg",
                "apfs", VOLUME_ID, ChildStorageBoundary.SAME_ACCEPTED_VOLUME,
                true, false, size, seconds, 456);
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static LocationPath path(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }
}
