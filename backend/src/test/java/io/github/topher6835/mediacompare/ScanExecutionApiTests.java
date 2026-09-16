package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;
import io.github.topher6835.mediacompare.scan.ScanRepository;
import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.ScanRunSource;
import io.github.topher6835.mediacompare.web.ScanExecutionResponse;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class ScanExecutionApiTests {

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

    @Autowired
    private ScanRunService scanRunService;

    @Autowired
    private JobRepository jobRepository;

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
    void createsAndReadsExecutionWithoutChangingThePendingScanRequest() throws Exception {
        ScanRunDetails scanRequest = createScanRequest("Unavailable source");
        ScanRun originalScanRun = scanRequest.scanRun();
        List<ScanRunSource> originalSources = scanRequest.sources();

        var creationResult = mockMvc.perform(post(executionPath(originalScanRun.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").isNumber())
                .andExpect(jsonPath("$.scanRunId").value(originalScanRun.id()))
                .andExpect(jsonPath("$.jobType").value("SCAN"))
                .andExpect(jsonPath("$.executionVersion").doesNotExist())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.currentStageType").value("DISCOVERY"))
                .andExpect(jsonPath("$.progressCompleted").value(0))
                .andExpect(jsonPath("$.progressTotal").isEmpty())
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.createdAtMs").isNumber())
                .andExpect(jsonPath("$.startedAtMs").doesNotExist())
                .andExpect(jsonPath("$.finishedAtMs").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.stages.length()").value(1))
                .andExpect(jsonPath("$.stages[0].stageType").value("DISCOVERY"))
                .andExpect(jsonPath("$.stages[0].resultJson").doesNotExist())
                .andExpect(jsonPath("$.stages[0].status").value("PENDING"))
                .andExpect(jsonPath("$.stages[0].progressCompleted").value(0))
                .andExpect(jsonPath("$.stages[0].progressTotal").isEmpty())
                .andExpect(jsonPath("$.stages[0].attemptCount").value(0))
                .andExpect(jsonPath("$.stages[0].startedAtMs").doesNotExist())
                .andExpect(jsonPath("$.stages[0].finishedAtMs").doesNotExist())
                .andExpect(jsonPath("$.stages[0].errorMessage").doesNotExist())
                .andReturn();

        ScanExecutionResponse response = jsonMapper.readValue(
                creationResult.getResponse().getContentAsString(), ScanExecutionResponse.class);
        assertEquals(executionPath(originalScanRun.id()), creationResult.getResponse().getHeader("Location"));

        Job job = jobRepository.findJobById(response.jobId()).orElseThrow();
        assertEquals(originalScanRun.id(), job.scanRunId());
        assertEquals("SCAN", job.jobType());
        assertEquals(1, job.executionVersion());
        assertEquals("PENDING", job.status());
        assertEquals("DISCOVERY", job.currentStageType());
        assertEquals(0, job.progressCompleted());
        assertNull(job.progressTotal());
        assertEquals(0, job.attemptCount());
        assertTrue(job.createdAtMs() > 0);
        assertNull(job.startedAtMs());
        assertNull(job.finishedAtMs());
        assertNull(job.errorMessage());

        List<JobStage> stages = jobRepository.findJobStagesByJobId(job.id());
        assertEquals(1, stages.size());
        JobStage stage = stages.getFirst();
        assertEquals(job.id().longValue(), stage.jobId());
        assertEquals("DISCOVERY", stage.stageType());
        assertNull(stage.resultJson());
        assertEquals("PENDING", stage.status());
        assertEquals(0, stage.progressCompleted());
        assertNull(stage.progressTotal());
        assertEquals(0, stage.attemptCount());
        assertEquals(job.createdAtMs(), stage.createdAtMs());
        assertNull(stage.startedAtMs());
        assertNull(stage.finishedAtMs());
        assertNull(stage.errorMessage());

        assertEquals(originalScanRun, scanRepository.findScanRunById(originalScanRun.id()).orElseThrow());
        assertEquals(originalSources, scanRepository.findScanRunSourcesByScanRunId(originalScanRun.id()));

        mockMvc.perform(get(executionPath(originalScanRun.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.id()))
                .andExpect(jsonPath("$.scanRunId").value(originalScanRun.id()))
                .andExpect(jsonPath("$.stages.length()").value(1))
                .andExpect(jsonPath("$.stages[0].stageType").value("DISCOVERY"));
    }

    @Test
    void getReturnsNotFoundForUnknownScanRunOrMissingExecution() throws Exception {
        mockMvc.perform(get(executionPath(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());

        ScanRunDetails scanRequest = createScanRequest("No execution");
        mockMvc.perform(get(executionPath(scanRequest.scanRun().id())))
                .andExpect(status().isNotFound());
    }

    @Test
    void postReturnsNotFoundForAnUnknownScanRun() throws Exception {
        mockMvc.perform(post(executionPath(Long.MAX_VALUE)))
                .andExpect(status().isNotFound());

        assertEquals(0, countRows("job"));
        assertEquals(0, countRows("job_stage"));
    }

    @Test
    void duplicatePostReturnsConflictWithoutCreatingMoreExecutionRows() throws Exception {
        ScanRunDetails scanRequest = createScanRequest("One execution");
        String path = executionPath(scanRequest.scanRun().id());

        mockMvc.perform(post(path)).andExpect(status().isCreated());
        mockMvc.perform(post(path)).andExpect(status().isConflict());

        assertEquals(1, countRows("job"));
        assertEquals(1, countRows("job_stage"));
    }

    @Test
    void differentScanRunsCanEachReceiveAnExecution() throws Exception {
        ScanRunDetails firstRun = createScanRequest("First");
        ScanRunDetails secondRun = createScanRequest("Second");

        var firstResult = mockMvc.perform(post(executionPath(firstRun.scanRun().id())))
                .andExpect(status().isCreated())
                .andReturn();
        var secondResult = mockMvc.perform(post(executionPath(secondRun.scanRun().id())))
                .andExpect(status().isCreated())
                .andReturn();

        ScanExecutionResponse firstExecution = jsonMapper.readValue(
                firstResult.getResponse().getContentAsString(), ScanExecutionResponse.class);
        ScanExecutionResponse secondExecution = jsonMapper.readValue(
                secondResult.getResponse().getContentAsString(), ScanExecutionResponse.class);

        assertNotEquals(firstExecution.jobId(), secondExecution.jobId());
        assertEquals(2, countRows("job"));
        assertEquals(2, countRows("job_stage"));
    }

    @Test
    void jobAndStageCreationShareOneTransactionalServiceBoundary() throws Exception {
        var createMethod = ScanExecutionService.class.getMethod("create", long.class);
        assertNotNull(createMethod.getAnnotation(Transactional.class));
    }

    private ScanRunDetails createScanRequest(String sourceName) {
        String rootPath = temporaryDirectory.resolve(sourceName).resolve("not-created").toAbsolutePath().toString();
        Source source = catalogRepository.insert(new Source(null, sourceName, rootPath, rootPath, 0, 1, 1));
        return scanRunService.create(List.of(source.id()));
    }

    private String executionPath(long scanRunId) {
        return "/api/scan-runs/" + scanRunId + "/execution";
    }

    private int countRows(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }
}
