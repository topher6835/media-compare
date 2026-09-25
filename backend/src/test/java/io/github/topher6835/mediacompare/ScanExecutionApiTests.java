package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.job.JobStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** V1 execution remains readable, but its old writer is closed after V6. */
@SpringBootTest
@AutoConfigureMockMvc
class ScanExecutionApiTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobRepository jobs;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM job_stage");
        jdbc.update("DELETE FROM job");
        jdbc.update("DELETE FROM scan_run_source");
        jdbc.update("DELETE FROM scan_run");
    }

    @Test
    void historicalV1ExecutionIsReadable() throws Exception {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (10, 'INDEX', 'PENDING', 1, '{}', 1)
                """);
        Job job = jobs.insert(new Job(null, 10L, "SCAN", 1, "PENDING", "DISCOVERY",
                0, null, 0, 1, null, null, null));
        jobs.insert(new JobStage(null, job.id(), "DISCOVERY", null, "PENDING",
                0, null, 0, 1, null, null, null));

        mockMvc.perform(get("/api/scan-runs/10/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.id()))
                .andExpect(jsonPath("$.stages[0].stageType").value("DISCOVERY"));
    }

    @Test
    void legacyCreationRejectsWithoutMutation() throws Exception {
        jdbc.update("""
                INSERT INTO scan_run (id, request_type, status, options_version,
                    options_json, created_at_ms)
                VALUES (11, 'INDEX', 'PENDING', 1, '{}', 1)
                """);
        mockMvc.perform(post("/api/scan-runs/11/execution"))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job", Integer.class));
    }

    @Test
    void unknownHistoricalExecutionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/scan-runs/999999/execution"))
                .andExpect(status().isNotFound());
    }
}
