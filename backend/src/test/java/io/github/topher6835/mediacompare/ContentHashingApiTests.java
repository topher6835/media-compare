package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Hashing reads the absolute resolved FileEntry identity after v3 assignment. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class ContentHashingApiTests extends V3ApiTestBase {
    @Test
    void hashesActualBytesOnceAndReusesCompletedHash() throws Exception {
        var source = source("hashed");
        byte[] bytes = "actual trusted bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(Path.of(source.rootPath()).resolve("a.bin"), bytes);
        completed(source);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, jdbc.queryForObject("SELECT digest_hex FROM content_hash", String.class));
        var before = jdbc.queryForList("SELECT * FROM content_hash");

        completed(source);

        assertEquals(before, jdbc.queryForList("SELECT * FROM content_hash"));
        assertEquals(1, count("content_hash"));
    }
}
