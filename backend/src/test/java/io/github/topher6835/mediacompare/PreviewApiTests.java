package io.github.topher6835.mediacompare;

import static io.github.topher6835.mediacompare.preview.PreviewTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.preview.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:preview-http-${random.uuid}?mode=memory&cache=shared&foreign_keys=on")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PreviewApiTests {
    @TempDir static Path directory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PreviewAssetRepository assets;
    @Autowired private MockMvc mvc;
    private static final byte[] BYTES = {0, 1, 2, 3, 4, (byte) 255};

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry properties) {
        properties.add("media-compare.preview-cache-root", () -> directory.resolve("cache").toString());
    }

    @BeforeEach
    void seedCatalog() throws IOException {
        Path cacheRoot = directory.resolve("cache");
        if (Files.exists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(cacheRoot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        seed(jdbc);
    }

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM preview_asset");
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM content_record");
    }

    @Test
    void servesExactBytesMediaTypeLengthImmutableHeadersAndConditionalValidatorWithoutWrites() throws Exception {
        PreviewAsset stored = published();
        Map<String, List<Map<String, Object>>> before = snapshot();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get(url(stored))).andExpect(status().isOk())
                    .andExpect(content().bytes(BYTES)).andExpect(content().contentType("image/png"))
                    .andExpect(header().string("Content-Length", "6"))
                    .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
                    .andExpect(header().string("ETag", "\"" + stored.assetKey() + "\""));
        }
        mvc.perform(get(url(stored)).header("If-None-Match", "\"" + stored.assetKey() + "\""))
                .andExpect(status().isNotModified()).andExpect(content().string(""))
                .andExpect(header().string("ETag", "\"" + stored.assetKey() + "\""));
        assertEquals(before, snapshot());
    }

    @Test
    void immutableKeyRemainsServableWhenSourceEvidenceBecomesStale() throws Exception {
        PreviewAsset stored = published();
        assertEquals(stored, assets.findCurrent(7, stored.definition(), PreviewKind.SMALL_THUMBNAIL).orElseThrow());
        jdbc.update("UPDATE file_entry SET observation_revision = 4 WHERE id = 7");
        assertTrue(assets.findCurrent(7, stored.definition(), PreviewKind.SMALL_THUMBNAIL).isEmpty());
        mvc.perform(get(url(stored))).andExpect(status().isOk()).andExpect(content().bytes(BYTES));
    }

    @Test
    void unknownAndMissingFilesReturn404WithoutStateChanges() throws Exception {
        PreviewAsset stored = assets.insert(asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), 6));
        Files.deleteIfExists(path(stored));
        var before = snapshot();
        mvc.perform(get("/api/previews/" + "a".repeat(64))).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(url(stored))).andExpect(status().isNotFound()).andExpect(content().string(""));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "ABC", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void malformedKeysReturn400(String key) throws Exception {
        mvc.perform(get("/api/previews/" + key)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.png", "/outside.png", "a/../outside.png", "a\\outside.png", "C:/outside.png",
            "different/safe.png"})
    void unsafeOrInconsistentPersistedPathsFailClosedEvenIfSqlConstraintsWereBypassed(String unsafe) throws Exception {
        PreviewAsset stored = published();
        // Simulate imported/corrupted metadata, beyond the normal SQL and domain write boundaries.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA ignore_check_constraints = ON");
                try (var update = connection.prepareStatement("UPDATE preview_asset SET relative_path = ?")) {
                    update.setString(1, unsafe);
                    update.executeUpdate();
                } finally {
                    statement.execute("PRAGMA ignore_check_constraints = OFF");
                }
            }
            return null;
        });
        var before = snapshot();
        mvc.perform(get(url(stored))).andExpect(status().isNotFound()).andExpect(content().string(""));
        assertEquals(before, snapshot());
    }

    @Test
    void finalSymlinkIsNeverServed() throws Exception {
        PreviewAsset stored = published();
        Files.delete(path(stored));
        Path outside = directory.resolve("outside.png");
        Files.write(outside, BYTES);
        symbolicLink(path(stored), outside);
        mvc.perform(get(url(stored))).andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void intermediateSymlinkCannotEscapeTheRoot() throws Exception {
        PreviewAsset stored = published();
        Path fanout = path(stored).getParent();
        Path outside = directory.resolve("outside-directory");
        Files.createDirectories(outside);
        Files.move(path(stored), outside.resolve(path(stored).getFileName()));
        Files.delete(fanout);
        symbolicLink(fanout, outside);
        mvc.perform(get(url(stored))).andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void linkedCacheRootIsNotServed() throws Exception {
        PreviewAsset stored = published();
        Path root = directory.resolve("cache");
        Path outside = directory.resolve("moved-cache");
        Files.move(root, outside);
        symbolicLink(root, outside);
        mvc.perform(get(url(stored))).andExpect(status().isNotFound()).andExpect(content().string(""));
    }

    @Test
    void directoryAndWrongSizeFailClosed() throws Exception {
        PreviewAsset stored = published();
        Files.write(path(stored), new byte[] {1});
        mvc.perform(get(url(stored))).andExpect(status().isNotFound());
        Files.delete(path(stored));
        Files.createDirectory(path(stored));
        mvc.perform(get(url(stored))).andExpect(status().isNotFound());
    }

    private PreviewAsset published() throws IOException {
        PreviewAsset asset = asset(evidence(), PreviewKind.SMALL_THUMBNAIL, definition(), BYTES.length);
        Files.createDirectories(path(asset).getParent());
        Files.write(path(asset), BYTES);
        return assets.insert(asset);
    }

    private static Path path(PreviewAsset asset) {
        return directory.resolve("cache").resolve(asset.relativePath());
    }

    private static String url(PreviewAsset asset) {
        return "/api/previews/" + asset.assetKey();
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("""
                SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                    AND name <> 'flyway_schema_history' ORDER BY name
                """, String.class)) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY rowid"));
        }
        return result;
    }

    private static void symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Host does not permit symbolic link creation: " + unavailable.getClass().getSimpleName());
        }
    }
}
