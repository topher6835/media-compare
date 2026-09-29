package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.preview.PreviewCacheResolver;
import io.github.topher6835.mediacompare.preview.PreviewCacheWriter;
import io.github.topher6835.mediacompare.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.util.ReflectionTestUtils;

class SessionStartupTests {
    @TempDir Path directory;

    @Test
    void selectedSessionOwnsDataSourceFlywayLockAndPreviewCache() throws Exception {
        Session session = Session.create(directory.resolve("with spaces"));
        try (var context = new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--media-compare.session-root=" + session.root(), "--logging.level.root=ERROR")) {
            assertEquals(session.root().resolve("catalog.db.lock"),
                    context.getBean(CatalogOwnership.class).lockPath());
            assertEquals(session.previewCacheRoot(),
                    ReflectionTestUtils.getField(context.getBean(PreviewCacheResolver.class), "configuredRoot"));
            assertEquals(session.previewCacheRoot(),
                    ReflectionTestUtils.getField(context.getBean(PreviewCacheWriter.class), "configuredRoot"));
            try (var connection = context.getBean(DataSource.class).getConnection();
                    var statement = connection.createStatement();
                    var files = statement.executeQuery("PRAGMA database_list")) {
                assertTrue(files.next());
                assertEquals(session.catalogPath(), Path.of(files.getString("file")));
                try (var migrations = connection.createStatement().executeQuery(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")) {
                    assertTrue(migrations.next());
                    assertEquals(9, migrations.getInt(1));
                }
            }
        }
        assertTrue(Files.isRegularFile(session.catalogPath()));
    }

    @Test
    void missingSessionFailsBeforeCatalogCreation() {
        assertThrows(Exception.class, () -> new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--media-compare.test-catalog-override=false", "--logging.level.root=ERROR"));
    }
}
