package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentCandidate;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentWriter;
import io.github.topher6835.mediacompare.catalog.CurrentMembershipAuthority;
import io.github.topher6835.mediacompare.catalog.StaleContentAssignmentException;

/** A stale candidate is skipped without blocking later trusted candidates. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IndexingRunApiTests.Hooks.class, ContentAssignmentStaleServiceTests.StaleWriterConfiguration.class})
class ContentAssignmentStaleServiceTests extends V3ApiTestBase {
    @org.springframework.beans.factory.annotation.Autowired StaleOnceWriter writer;

    @Test
    void staleCandidateDoesNotBlockLaterAssignment() throws Exception {
        var source = source("stale-assignment");
        Path root = Path.of(source.rootPath());
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        writer.skipNext();

        completed(source);

        assertEquals(2, count("file_entry"));
        assertEquals(1, count("content_record"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_entry WHERE current_content_id IS NULL", Long.class));
        completed(source);
        assertEquals(2, count("content_record"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StaleWriterConfiguration {
        @Bean @Primary StaleOnceWriter staleWriter(CatalogRepository catalog, CurrentMembershipAuthority authority) {
            return new StaleOnceWriter(catalog, authority);
        }
    }

    static class StaleOnceWriter extends ContentAssignmentWriter {
        private boolean staleNext;
        StaleOnceWriter(CatalogRepository catalog, CurrentMembershipAuthority authority) {
            super(catalog, authority);
        }
        void skipNext() { staleNext = true; }
        @Override @Transactional
        public void assign(ContentAssignmentCandidate candidate, long createdAtMs) {
            if (staleNext) {
                staleNext = false;
                throw new StaleContentAssignmentException(candidate.fileEntryId());
            }
            super.assign(candidate, createdAtMs);
        }
    }
}
