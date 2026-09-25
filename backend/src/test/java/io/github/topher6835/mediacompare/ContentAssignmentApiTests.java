package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Assignment consumes trusted resolved memberships in the v3 pipeline. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class ContentAssignmentApiTests extends V3ApiTestBase {
    @Test
    void assignsOneContentRecordPerPhysicalFileAndReusesIt() throws Exception {
        var source = source("assigned");
        Files.writeString(Path.of(source.rootPath()).resolve("a.txt"), "a");
        Files.writeString(Path.of(source.rootPath()).resolve("b.txt"), "b");
        completed(source);
        var before = jdbc.queryForList("SELECT id, current_content_id FROM file_entry ORDER BY id");
        assertEquals(2, count("content_record"));
        assertTrue(before.stream().allMatch(row -> row.get("current_content_id") != null));

        completed(source);

        assertEquals(before, jdbc.queryForList("SELECT id, current_content_id FROM file_entry ORDER BY id"));
        assertEquals(2, count("content_record"));
    }
}
