package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunSource;
import io.github.topher6835.mediacompare.web.ScanRunResponse;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class ScanRunApiTests {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private ScanRepository scanRepository;

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
    void createsAndReadsARequestForOneSourceWithInitialState() throws Exception {
        Source source = insertSource("Photos", 3);

        var creationResult = createScanRun(List.of(source.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.requestType").value("INDEX"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAtMs").isNumber())
                .andExpect(jsonPath("$.optionsVersion").doesNotExist())
                .andExpect(jsonPath("$.optionsJson").doesNotExist())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].sourceId").value(source.id()))
                .andExpect(jsonPath("$.sources[0].status").value("PENDING"))
                .andExpect(jsonPath("$.sources[0].sourceLocationRevision").value(3))
                .andExpect(jsonPath("$.sources[0].traversalGeneration").value(0))
                .andExpect(jsonPath("$.sources[0].completedGeneration").isEmpty())
                .andReturn();

        ScanRunResponse response = jsonMapper.readValue(
                creationResult.getResponse().getContentAsString(), ScanRunResponse.class);
        assertEquals("/api/scan-runs/" + response.id(), creationResult.getResponse().getHeader("Location"));

        ScanRun persistedRun = scanRepository.findScanRunById(response.id()).orElseThrow();
        assertEquals("INDEX", persistedRun.requestType());
        assertEquals("PENDING", persistedRun.status());
        assertEquals(1, persistedRun.optionsVersion());
        assertEquals("{}", persistedRun.optionsJson());
        assertTrue(persistedRun.createdAtMs() > 0);
        assertNull(persistedRun.workingSetId());
        assertNull(persistedRun.startedAtMs());
        assertNull(persistedRun.finishedAtMs());
        assertNull(persistedRun.errorMessage());

        ScanRunSource persistedSource = scanRepository.findScanRunSourcesByScanRunId(response.id()).getFirst();
        assertEquals(source.id().longValue(), persistedSource.sourceId());
        assertEquals("PENDING", persistedSource.status());
        assertEquals(source.locationRevision(), persistedSource.sourceLocationRevision());
        assertEquals(0, persistedSource.traversalGeneration());
        assertNull(persistedSource.completedGeneration());
        assertNull(persistedSource.startedAtMs());
        assertNull(persistedSource.completedAtMs());
        assertNull(persistedSource.errorMessage());

        assertEquals(0, countRows("job"));

        mockMvc.perform(get("/api/scan-runs/{id}", response.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.id()))
                .andExpect(jsonPath("$.requestType").value("INDEX"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sources[0].sourceId").value(source.id()))
                .andExpect(jsonPath("$.sources[0].sourceLocationRevision").value(3));
    }

    @Test
    void createsARequestForMultipleSourcesInAscendingSourceIdOrder() throws Exception {
        Source firstSource = insertSource("First", 0);
        Source secondSource = insertSource("Second", 2);

        createScanRun(List.of(secondSource.id(), firstSource.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].sourceId").value(firstSource.id()))
                .andExpect(jsonPath("$.sources[0].sourceLocationRevision").value(0))
                .andExpect(jsonPath("$.sources[1].sourceId").value(secondSource.id()))
                .andExpect(jsonPath("$.sources[1].sourceLocationRevision").value(2));

        ScanRun scanRun = scanRepository.findScanRunById(firstScanRunId()).orElseThrow();
        mockMvc.perform(get("/api/scan-runs/{id}", scanRun.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].sourceId").value(firstSource.id()))
                .andExpect(jsonPath("$.sources[1].sourceId").value(secondSource.id()));
    }

    @Test
    void returnsNotFoundForAnUnknownScanRun() throws Exception {
        mockMvc.perform(get("/api/scan-runs/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNullAndEmptySourceLists() throws Exception {
        mockMvc.perform(post("/api/scan-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIds\":null}"))
                .andExpect(status().isBadRequest());

        createScanRun(List.of())
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonPositiveSourceIds() throws Exception {
        createScanRun(List.of(0L))
                .andExpect(status().isBadRequest());
        createScanRun(List.of(-1L))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateSourceIds() throws Exception {
        Source source = insertSource("Duplicate", 0);

        createScanRun(List.of(source.id(), source.id()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundWhenARequestedSourceDoesNotExist() throws Exception {
        createScanRun(List.of(Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesEverySourceBeforePersistingAnyPartOfTheRequest() throws Exception {
        Source source = insertSource("Existing", 0);

        createScanRun(List.of(source.id(), Long.MAX_VALUE))
                .andExpect(status().isNotFound());

        assertEquals(0, countRows("scan_run"));
        assertEquals(0, countRows("scan_run_source"));
    }

    @Test
    void repeatedRequestsForTheSameSourcesCreateDistinctScanRuns() throws Exception {
        Source source = insertSource("Repeat", 0);

        createScanRun(List.of(source.id())).andExpect(status().isCreated());
        createScanRun(List.of(source.id())).andExpect(status().isCreated());

        List<Long> scanRunIds = jdbcTemplate.queryForList("SELECT id FROM scan_run ORDER BY id", Long.class);
        assertEquals(2, scanRunIds.size());
        assertNotEquals(scanRunIds.get(0), scanRunIds.get(1));
        assertEquals(2, countRows("scan_run_source"));
    }

    private ResultActions createScanRun(List<Long> sourceIds) throws Exception {
        String requestJson = jsonMapper.writeValueAsString(Map.of("sourceIds", sourceIds));
        return mockMvc.perform(post("/api/scan-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson));
    }

    private Source insertSource(String name, long locationRevision) {
        String rootPath = temporaryDirectory.resolve(name).toAbsolutePath().toString();
        return catalogRepository.insert(new Source(
                null, name, rootPath, rootPath, locationRevision, 1, 1));
    }

    private long firstScanRunId() {
        return jdbcTemplate.queryForObject("SELECT id FROM scan_run ORDER BY id LIMIT 1", Long.class);
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
