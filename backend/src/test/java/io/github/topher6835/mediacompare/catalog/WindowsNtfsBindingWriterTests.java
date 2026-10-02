package io.github.topher6835.mediacompare.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.scan.Version3ScanExecutionService;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database transitions use typed fake observations; no Windows native API is invoked here. */
class WindowsNtfsBindingWriterTests {
    @TempDir Path directory;

    @Test
    void acceptanceBindUnbindAndFixedRootRebindPreserveHistory() {
        String url = "jdbc:sqlite:" + directory.resolve("catalog.db") + "?foreign_keys=on";
        try (var app = new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--logging.level.root=ERROR")) {
            var contexts = app.getBean(LocationContextRepository.class);
            var sources = app.getBean(CatalogRepository.class);
            var periods = app.getBean(SourceBindingPeriodRepository.class);
            var writer = app.getBean(WindowsNtfsBindingWriter.class);
            var anchor = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "D:\\");
            String contextId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            contexts.insert(new LocationContext(contextId, new LocationPathCodec().encode(anchor),
                    LocationKeyCodec.encode(anchor).value(), LocationContext.LifecycleStatus.ACTIVE,
                    LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, 1, 1));
            var source = sources.insert(new Source(null, "Photos", "D:\\Photos", "D:\\Photos", 0, 1, 1));
            var anchorId = new WindowsNtfsIdentity("000000000000abcd", "00000000000000000000000000000001");
            var rootId = new WindowsNtfsIdentity("000000000000abcd", "00000000000000000000000000000002");
            var context = writer.accept(contextId, 0, anchorId, 2);
            assertThrows(IllegalArgumentException.class, () -> writer.bind(source.id(), 0, source.rootPath(),
                    contextId, 1, anchorId,
                    new WindowsNtfsIdentity("000000000000eeee", rootId.fileId()), 3));
            var bound = writer.bind(source.id(), 0, source.rootPath(), contextId, 1, anchorId, rootId, 3);
            assertEquals("win-drive", bound.rootPathDialect());
            assertEquals(1, bound.locationRevision());
            assertEquals("ntfs", CurrentLocationAuthority.requirePersisted(bound, context).fileSystemType());
            assertEquals(1, periods.findBySourceId(source.id()).size());
            if (!HostFileSystems.isWindows()) {
                JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
                jdbc.update("""
                        INSERT INTO scan_run (id, request_type, status, options_version,
                            options_json, created_at_ms)
                        VALUES (10, 'INDEX', 'PENDING', 1, '{}', 1)
                        """);
                jdbc.update("""
                        INSERT INTO scan_run_source (id, scan_run_id, source_id, status,
                            source_location_revision, traversal_generation)
                        VALUES (11, 10, ?, 'PENDING', 1, 0)
                        """, source.id());
                assertThrows(Version2ExecutionConflictException.class,
                        () -> app.getBean(Version3ScanExecutionService.class).create(10));
            }

            var unbound = app.getBean(SourceUnbindingService.class).unbind(source.id(), 1, 4);
            assertEquals(SourcePreparationState.REBIND_REQUIRED, SourcePreparationState.from(unbound));
            assertEquals(2, unbound.locationRevision());
            var rebound = writer.rebind(source.id(), 2, contextId, 1, anchorId, rootId, 5);
            assertEquals(3, rebound.locationRevision());
            assertEquals(2, periods.findBySourceId(source.id()).size());
            assertEquals(3, periods.findOpenBySourceId(source.id()).orElseThrow()
                    .boundSourceLocationRevision());
        }
    }
}
