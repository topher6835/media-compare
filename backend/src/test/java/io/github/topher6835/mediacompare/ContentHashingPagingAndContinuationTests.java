package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.analysis.StaleContentHashException;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;

/** Hashing pages beyond the first batch and continues after candidate failures. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IndexingRunApiTests.Hooks.class, ContentHashingPagingAndContinuationTests.HashHooks.class})
class ContentHashingPagingAndContinuationTests extends V3ApiTestBase {
    @Test
    void hashesLaterPageAfterSkippedAndFailedCandidates() throws Exception {
        var source = source("paged-hashes");
        Path root = Path.of(source.rootPath());
        for (int index = 0; index < 252; index++) {
            Files.writeString(root.resolve("candidate-" + index + ".txt"), "file " + index);
        }

        var accepted = completed(source);

        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, accepted.job().id()));
        assertEquals(252, count("file_entry"));
        assertEquals(250, count("content_hash"));
        assertEquals(250, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record WHERE analysis_type='CONTENT_HASH'", Long.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HashHooks {
        @Bean @Primary ContentHashFileHasher scriptedHasher() {
            return new ContentHashFileHasher() {
                @Override public String hash(ContentHashCandidate candidate) throws IOException {
                    if (candidate.locationPath().contains("candidate-0.txt"))
                        throw new StaleContentHashException(candidate.fileEntryId(), "fixture stale");
                    if (candidate.locationPath().contains("candidate-250.txt"))
                        throw new IOException("fixture read failure");
                    return super.hash(candidate);
                }
            };
        }
    }
}
