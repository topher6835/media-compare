package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** v3 reconciles presence on memberships after completed discovery. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class ReconciliationApiTests extends V3ApiTestBase {
    @Test
    void missingFileChangesMembershipAndRetainsPhysicalHistory() throws Exception {
        var source = source("missing");
        Path file = Files.writeString(Path.of(source.rootPath()).resolve("a.txt"), "bytes");
        completed(source);
        var before = jdbc.queryForMap("SELECT id, current_content_id FROM file_entry");
        Files.delete(file);

        completed(source);

        assertEquals(before, jdbc.queryForMap("SELECT id, current_content_id FROM file_entry"));
        assertEquals("MISSING", jdbc.queryForObject(
                "SELECT presence_status FROM source_membership", String.class));
    }

    @Test
    void scanningOneSourceDoesNotSweepAnotherSource() throws Exception {
        var first = source("first");
        var second = source("second");
        Path firstFile = Files.writeString(Path.of(first.rootPath()).resolve("a.txt"), "first");
        Files.writeString(Path.of(second.rootPath()).resolve("b.txt"), "second");
        completed(first, second);
        Files.delete(firstFile);

        completed(first);

        assertEquals("MISSING", jdbc.queryForObject(
                "SELECT presence_status FROM source_membership WHERE source_id = ?", String.class, first.id()));
        assertEquals("PRESENT", jdbc.queryForObject(
                "SELECT presence_status FROM source_membership WHERE source_id = ?", String.class, second.id()));
    }
}
