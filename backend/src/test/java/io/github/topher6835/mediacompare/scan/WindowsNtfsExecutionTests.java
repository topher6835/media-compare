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

/** Real NTFS acceptance checks. These are skipped, not simulated, on other hosts. */
class WindowsNtfsExecutionTests {
    @TempDir Path directory;

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
