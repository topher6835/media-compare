package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.analysis.StaleContentHashException;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.scan.ReconciliationService;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContentHashingPagingAndContinuationTests.ScriptedHasherConfiguration.class)
class ContentHashingPagingAndContinuationTests {

    private static final int PAGE_SIZE = 250;
    private static final int CANDIDATE_COUNT = 252;
    private static final String VALID_DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private ScanRepository scanRepository;

    @Autowired
    private ScanRunService scanRunService;

    @Autowired
    private ScanExecutionService scanExecutionService;

    @Autowired
    private ReconciliationService reconciliationService;

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
    void pagesPastStaleAndFailedCandidatesAndContinuesOnTheNextPage() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("paged"));
        String rootPath = root.toAbsolutePath().toString();
        Source source = catalogRepository.insert(new Source(
                null, "Source", rootPath, rootPath, 0, 1, 1));
        ScanRunDetails scanRun = scanRunService.create(List.of(source.id()));
        scanExecutionService.create(scanRun.scanRun().id());
        scanExecutionService.executeDiscovery(scanRun.scanRun().id());
        reconciliationService.execute(scanRun.scanRun().id());
        ScanRunSource scanRunSource = scanRepository.findScanRunSourcesByScanRunId(
                scanRun.scanRun().id()).getFirst();

        for (int index = 0; index < CANDIDATE_COUNT; index++) {
            ContentRecord content = catalogRepository.insert(new ContentRecord(null, 10, 1));
            catalogRepository.insert(new FileEntry(
                    null, source.id(), "candidate-" + index, "candidate-" + index,
                    content.id(), "PRESENT", 10, 100L, 200, 0, 1, 1,
                    scanRunSource.id(), scanRunSource.completedGeneration()));
        }

        List<ContentHashCandidate> firstPage = catalogRepository.findContentHashCandidates(
                scanRunSource.id(), scanRunSource.completedGeneration(), 0, PAGE_SIZE);
        assertEquals(PAGE_SIZE, firstPage.size());
        for (int index = 1; index < firstPage.size(); index++) {
            assertTrue(firstPage.get(index - 1).fileEntryId() < firstPage.get(index).fileEntryId());
        }

        mockMvc.perform(post("/api/scan-runs/{id}/content-hashing", scanRun.scanRun().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(250))
                .andExpect(jsonPath("$.cachedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1));

        assertEquals(250, countRows("analysis_record"));
        assertEquals(250, countRows("content_hash"));
        assertEquals(CANDIDATE_COUNT, countRows("content_record"));

        mockMvc.perform(post("/api/scan-runs/{id}/content-hashing", scanRun.scanRun().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hashedCount").value(0))
                .andExpect(jsonPath("$.cachedCount").value(250))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1));
    }

    private long countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedHasherConfiguration {

        @Bean
        @Primary
        ScriptedContentHashFileHasher scriptedContentHashFileHasher() {
            return new ScriptedContentHashFileHasher();
        }
    }

    static class ScriptedContentHashFileHasher extends ContentHashFileHasher {

        @Override
        public String hash(Path sourceRoot, ContentHashCandidate candidate) throws IOException {
            if ("candidate-0".equals(candidate.relativePath())) {
                throw new StaleContentHashException(candidate.fileEntryId(), "simulated stale evidence");
            }
            if ("candidate-250".equals(candidate.relativePath())) {
                throw new IOException("simulated read failure");
            }
            return VALID_DIGEST;
        }
    }
}
