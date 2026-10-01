package io.github.topher6835.mediacompare.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.catalog.SourcePreparationService;
import io.github.topher6835.mediacompare.catalog.SourceService;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsHostFileSystem;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityOutcome;
import io.github.topher6835.mediacompare.filesystem.CheckoutTempDirFactory;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsNative;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.location.*;
import io.github.topher6835.mediacompare.matching.*;
import io.github.topher6835.mediacompare.preview.*;
import io.github.topher6835.mediacompare.session.Session;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Real NTFS acceptance checks. These are skipped, not simulated, on other hosts. */
@EnabledOnOs(OS.WINDOWS)
class WindowsNtfsExecutionTests {
    @TempDir(factory = CheckoutTempDirFactory.class) Path directory;

    @Test
    void realSessionScansImagesReadsOriginalsAndPreservesBindingHistory() throws Exception {
        Path session = Session.create(directory.resolve("session")).root();
        Path sourceRoot = Files.createDirectory(directory.resolve("MixedCaseSource"));
        Path nested = Files.createDirectories(sourceRoot.resolve("Nested"));
        Path jpeg = sourceRoot.resolve("Photo.JPG");
        Path png = nested.resolve("Alpha.PNG");
        assertTrue(ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "JPEG", jpeg.toFile()));
        assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB), "PNG", png.toFile()));
        Files.copy(jpeg, nested.resolve("Copy.JPG"));
        Files.writeString(sourceRoot.resolve("notes.txt"), "non-media");
        byte[] original = Files.readAllBytes(jpeg);
        var originalTime = Files.getLastModifiedTime(jpeg);
        try (var app = new SpringApplicationBuilder(MediaCompareApplication.class).web(WebApplicationType.NONE)
                .run("--media-compare.session-root=" + session, "--media-compare.test-catalog-override=false",
                        "--logging.level.root=ERROR")) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            var sources = app.getBean(SourceService.class);
            var source = sources.register("Real Windows", sourceRoot.toString());
            assertThrows(Version2ExecutionConflictException.class, () -> app.getBean(IndexingRunService.class)
                    .start(UUID.randomUUID().toString(), List.of(source.id())));
            var bound = app.getBean(SourcePreparationService.class).prepare(source.id());
            assertEquals(SourcePreparationState.READY, SourcePreparationState.from(bound));
            assertEquals(bound, app.getBean(SourcePreparationService.class).prepare(source.id()));
            assertEquals(sourceRoot.toString(), bound.rootPath());
            var root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, sourceRoot.toString());
            assertEquals(LocationKeyCodec.encode(root).value(), bound.rootPathKey());
            var codec = new WindowsNtfsEvidenceCodec();
            var binding = codec.decodeSource(bound.bindingEvidenceJson());
            var context = app.getBean(LocationContextRepository.class).findById(bound.boundLocationContextId()).orElseThrow();
            var acceptance = codec.decodeContext(context.continuityEvidenceJson());
            assertEquals(bound.locationRevision(), binding.sourceRevision());
            assertEquals(context.revision(), binding.contextRevision());
            assertEquals(context.revision(), acceptance.contextRevision());
            assertEquals(WindowsNtfsNative.observe(sourceRoot).identity(), binding.identity());
            assertEquals(WindowsNtfsNative.observe(sourceRoot.getRoot()).identity(), acceptance.identity());
            assertFalse(bound.bindingEvidenceJson().contains("macOsApfs"));
            assertFalse(context.continuityEvidenceJson().contains("macOsApfs"));
            String alternateDriveCase = sourceRoot.toString().substring(0, 1).toLowerCase(java.util.Locale.ROOT)
                    + sourceRoot.toString().substring(1);
            var alternate = sources.register("Alternate drive spelling", alternateDriveCase);
            assertThrows(SourcePreparationException.class,
                    () -> app.getBean(SourcePreparationService.class).prepare(alternate.id()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM location_context", Integer.class));
            assertEquals(1, count(jdbc, "source_binding_period"));
            var first = scan(app, source.id());
            assertFalse(first.completedWithIssues());
            assertEquals(4, first.hashingResult().hashedCount());
            assertEquals(4, count(jdbc, "file_entry"));
            assertEquals(4, count(jdbc, "content_hash"));
            assertTrue(jdbc.queryForList("SELECT location_path FROM file_entry", String.class).stream()
                    .allMatch(path -> new LocationPathCodec().decode(path).dialect() == LocationDialect.WINDOWS_DRIVE));
            var repeat = scan(app, source.id());
            assertEquals(4, repeat.hashingResult().cachedCount());
            assertEquals(4, count(jdbc, "file_entry"));
            assertEquals(4, count(jdbc, "content_record"));
            assertEquals(4, count(jdbc, "source_membership"));

            var metadata = app.getBean(ImageIoMediaMetadataAnalyzer.class);
            var candidates = app.getBean(MediaMetadataCandidateRepository.class);
            for (var candidate : candidates.findCandidates(ImageIoMediaMetadataDefinition.definition(), 0, 100)) {
                assertTrue(metadata.analyze(candidate).isPresent());
            }
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record WHERE analysis_type='MEDIA_METADATA' AND result_json LIKE '%AVAILABLE%'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record WHERE analysis_type='MEDIA_METADATA' AND result_json LIKE '%UNSUPPORTED%'", Integer.class));
            var thumbnails = app.getBean(SmallThumbnailService.class);
            for (var path : List.of("Photo.JPG", "Nested/Alpha.PNG")) {
                long fileId = jdbc.queryForObject("SELECT file_entry_id FROM source_membership WHERE relative_path=?", Long.class, path);
                var result = thumbnails.generate(fileId);
                assertEquals(ThumbnailGenerationResult.Outcome.GENERATED, result.outcome());
                Path cache = session.resolve("cache/previews").resolve(result.asset().relativePath());
                assertTrue(Files.isRegularFile(cache));
                assertNotNull(ImageIO.read(cache.toFile()));
                assertEquals(ThumbnailGenerationResult.Outcome.REUSED, thumbnails.generate(fileId).outcome());
            }
            assertArrayEquals(original, Files.readAllBytes(jpeg));
            assertEquals(originalTime, Files.getLastModifiedTime(jpeg));
            String digest = jdbc.queryForObject("SELECT h.digest_hex FROM content_hash h JOIN analysis_record a ON a.id=h.analysis_record_id JOIN file_entry f ON f.current_content_id=a.content_record_id JOIN source_membership m ON m.file_entry_id=f.id WHERE m.relative_path='Photo.JPG'", String.class);
            var duplicate = app.getBean(ExactDuplicateService.class).findGroup(digest).orElseThrow();
            assertEquals(2, duplicate.summary().presentOccurrenceCount());
            var ids = jdbc.queryForList("SELECT file_entry_id FROM source_membership WHERE relative_path IN ('Photo.JPG','Nested/Copy.JPG') ORDER BY file_entry_id", Long.class);
            assertEquals(CleanupPreflightStatus.READY, app.getBean(CleanupPreflightService.class)
                    .preflight(digest, new CleanupPreflightRequest(ids.getFirst(), List.of(ids.getLast()))).status());

            var unbound = app.getBean(SourceUnbindingService.class).unbind(source.id(), bound.locationRevision(),
                    Math.max(System.currentTimeMillis(), bound.updatedAtMs()));
            assertEquals(SourcePreparationState.REBIND_REQUIRED, SourcePreparationState.from(unbound));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership WHERE applicability_status='ACTIVE'", Integer.class));
            var rebound = app.getBean(WindowsNtfsSourcePreparationService.class).rebind(source.id(), context.id());
            assertEquals(unbound.locationRevision() + 1, rebound.locationRevision());
            assertEquals(bound.rootPathKey(), rebound.rootPathKey());
            assertEquals(2, count(jdbc, "source_binding_period"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_binding_period WHERE unbound_at_ms IS NULL", Integer.class));
            scan(app, source.id());
            assertEquals(4, count(jdbc, "file_entry"));
            assertEquals(4, count(jdbc, "source_membership"));
        }
    }

    @Test
    void hashingAndMetadataRejectReplacementWithUnchangedSizeAndTimestamp() throws Exception {
        Path file = Files.writeString(directory.resolve("file.jpg"), "first");
        var time = Files.getLastModifiedTime(file);
        var location = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, file.toString());
        String path = new LocationPathCodec().encode(location);
        String key = LocationKeyCodec.encode(location).value();
        var hashCandidate = new ContentHashCandidate(1, 1, 1, 1, UUID.randomUUID().toString(), 1, 0,
                path, key, 0, 5, time.toInstant().getEpochSecond(), time.toInstant().getNano(), 1);
        var metadataCandidate = new MediaMetadataFileCandidate(1, 5, 1, 1, 1, 1, hashCandidate.contextId(), 1, 0,
                path, key, 0, 5, time.toInstant().getEpochSecond(), time.toInstant().getNano());
        var validator = new MediaMetadataFileEvidenceValidator();
        var before = validator.captureBeforeExtraction(metadataCandidate);
        var hasher = new ContentHashFileHasher() {
            boolean replaced;
            @Override protected int read(SeekableByteChannel channel, ByteBuffer buffer) throws java.io.IOException {
                int count = super.read(channel, buffer);
                if (!replaced) {
                    replaced = true;
                    Path replacement = Files.writeString(directory.resolve("replacement.jpg"), "other");
                    Files.setLastModifiedTime(replacement, time);
                    Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return count;
            }
        };
        assertThrows(StaleContentHashException.class, () -> hasher.hash(hashCandidate));
        assertThrows(StaleMediaMetadataEvidenceException.class,
                () -> validator.validateAfterExtraction(metadataCandidate, before));
    }

    private static IndexingRunDetails scan(org.springframework.context.ConfigurableApplicationContext app,
            long sourceId) throws Exception {
        long id = app.getBean(IndexingRunService.class).start(UUID.randomUUID().toString(), List.of(sourceId)).run().scanRun().id();
        var reads = app.getBean(IndexingRunReadService.class);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        IndexingRunDetails result;
        do {
            result = reads.require(id);
            if (!List.of("PENDING", "RUNNING").contains(result.job().status())) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals("COMPLETED", result.job().status(), result.job().errorMessage());
        return result;
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void preparedSourceScansAndReplacementRootLosesAuthority() throws Exception {
        Assumptions.assumeTrue(HostFileSystems.current() instanceof WindowsNtfsHostFileSystem);
        Assumptions.assumeTrue("NTFS".equalsIgnoreCase(Files.getFileStore(directory).type()));
        Path sourceRoot = Files.createDirectory(directory.resolve("source"));
        Files.writeString(sourceRoot.resolve("photo.jpg"), "trusted bytes");
        String url = "jdbc:sqlite:" + directory.resolve("catalog.db") + "?foreign_keys=on";
        try (var app = new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--logging.level.root=ERROR")) {
            var source = app.getBean(SourceService.class).register("Windows Photos", sourceRoot.toString());
            var bound = app.getBean(SourcePreparationService.class).prepare(source.id());
            assertEquals("win-drive", bound.rootPathDialect());
            assertNotNull(bound.boundLocationContextId());
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            jdbc.update("""
                    INSERT INTO scan_run (id, request_type, status, options_version,
                        options_json, created_at_ms)
                    VALUES (10, 'INDEX', 'PENDING', 1, '{}', 1)
                    """);
            jdbc.update("""
                    INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                        source_location_revision, traversal_generation)
                    VALUES (11, 10, ?, 'PENDING', ?, 0)
                    """, source.id(), bound.locationRevision());
            var execution = app.getBean(Version3ScanExecutionService.class);
            execution.create(10);
            assertEquals("COMPLETED", execution.run(10).job().status());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry WHERE location_identity_status='RESOLVED'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM content_hash", Integer.class));

            Files.move(sourceRoot, directory.resolve("old-source"));
            Files.createDirectory(sourceRoot);
            assertEquals(ScanAuthorityOutcome.STALE,
                    app.getBean(Version3AuthorityCapture.class).capture(source.id()).outcome());
        }
    }
}
