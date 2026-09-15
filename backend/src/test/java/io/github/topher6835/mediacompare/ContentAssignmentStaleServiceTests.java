package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentCandidate;
import io.github.topher6835.mediacompare.catalog.ContentAssignmentWriter;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.StaleContentAssignmentException;
import io.github.topher6835.mediacompare.scan.ReconciliationService;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContentAssignmentStaleServiceTests.StaleWriterConfiguration.class)
class ContentAssignmentStaleServiceTests {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private ScanRunService scanRunService;

    @Autowired
    private ScanExecutionService scanExecutionService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private StaleOnceContentAssignmentWriter contentAssignmentWriter;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
        jdbcTemplate.update("DELETE FROM file_entry");
        jdbcTemplate.update("DELETE FROM scan_run_source");
        jdbcTemplate.update("DELETE FROM working_set_content");
        jdbcTemplate.update("DELETE FROM analysis_record");
        jdbcTemplate.update("DELETE FROM job");
        jdbcTemplate.update("DELETE FROM scan_run");
        jdbcTemplate.update("DELETE FROM working_set");
        jdbcTemplate.update("DELETE FROM content_record");
        jdbcTemplate.update("DELETE FROM source");
    }

    @Test
    void staleCandidateIsCountedAndDoesNotStopLaterCandidates() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("source"));
        String rootPath = root.toAbsolutePath().toString();
        Source source = catalogRepository.insert(new Source(null, "Source", rootPath, rootPath, 0, 1, 1));
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        scanExecutionService.create(scanRun.scanRun().id());
        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        reconciliationService.execute(scanRun.scanRun().id());
        ScanRunSource scanRunSource = scanRun.sources().getFirst();

        for (int index = 0; index < 2; index++) {
            catalogRepository.insert(new FileEntry(
                    null, source.id(), "candidate-" + index, "candidate-" + index, null,
                    "PRESENT", 10, 100L, 200, 0, 10, 20, scanRunSource.id(), 1L));
        }
        contentAssignmentWriter.skipNext();

        mockMvc.perform(post("/api/scan-runs/{id}/content-assignment", scanRun.scanRun().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(1));

        assertEquals(1, countRows("content_record"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_entry WHERE current_content_id IS NULL", Long.class));

        mockMvc.perform(post("/api/scan-runs/{id}/content-assignment", scanRun.scanRun().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(1))
                .andExpect(jsonPath("$.skippedCount").value(0));
        assertEquals(2, countRows("content_record"));
    }

    private long countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StaleWriterConfiguration {

        @Bean
        @Primary
        StaleOnceContentAssignmentWriter staleOnceContentAssignmentWriter(CatalogRepository catalogRepository) {
            return new StaleOnceContentAssignmentWriter(catalogRepository);
        }
    }

    static class StaleOnceContentAssignmentWriter extends ContentAssignmentWriter {

        private boolean staleNext;

        StaleOnceContentAssignmentWriter(CatalogRepository catalogRepository) {
            super(catalogRepository);
        }

        void skipNext() {
            staleNext = true;
        }

        @Override
        @Transactional
        public void assign(ContentAssignmentCandidate candidate, long createdAtMs) {
            if (staleNext) {
                staleNext = false;
                throw new StaleContentAssignmentException(candidate.fileEntryId());
            }
            super.assign(candidate, createdAtMs);
        }
    }
}
