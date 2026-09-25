package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Discovery writes now belong to the trusted v3 indexing path. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class DiscoveryApiTests extends V3ApiTestBase {
    @Test
    void discoversNestedRegularFilesWithSourceMemberships() throws Exception {
        var source = source("nested");
        Path root = Path.of(source.rootPath());
        Files.createDirectory(root.resolve("child"));
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("child/b.txt"), "b");

        completed(source);

        assertEquals(2, count("file_entry"));
        assertEquals(2, count("source_membership"));
        assertEquals(List.of("a.txt", "child/b.txt"), jdbc.queryForList(
                "SELECT relative_path FROM source_membership ORDER BY relative_path", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry WHERE location_identity_status='RESOLVED'", Long.class));
    }

    @Test
    void unchangedSecondObservationKeepsPhysicalAndContentIdentity() throws Exception {
        var source = source("reobserve");
        Files.writeString(Path.of(source.rootPath()).resolve("a.txt"), "a");
        completed(source);
        var before = jdbc.queryForMap("SELECT id, current_content_id, observation_revision FROM file_entry");

        completed(source);

        assertEquals(before, jdbc.queryForMap("SELECT id, current_content_id, observation_revision FROM file_entry"));
        assertEquals(1, count("source_membership"));
    }
}
