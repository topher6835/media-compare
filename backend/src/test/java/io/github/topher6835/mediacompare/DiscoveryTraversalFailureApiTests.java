package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** A failed v3 traversal cannot authorize a negative presence claim. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class DiscoveryTraversalFailureApiTests extends V3ApiTestBase {
    @Test
    void failureAfterAdmissionLeavesExistingMembershipPresent() throws Exception {
        var source = source("failure");
        Path root = Path.of(source.rootPath());
        Files.writeString(root.resolve("a.txt"), "a");
        completed(source);
        long fileId = jdbc.queryForObject("SELECT id FROM file_entry", Long.class);

        var accepted = pending(source);
        Path moved = root.resolveSibling("moved");
        Files.move(root, moved);
        executor.runAccepted();

        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, accepted.job().id()));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership", String.class));
        assertEquals(fileId, jdbc.queryForObject("SELECT file_entry_id FROM source_membership", Long.class));
    }
}
