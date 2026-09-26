package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Map;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.web.SourceResponse;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.topher6835.mediacompare.catalog.SourceUnbindingService;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SourcePreparationServiceTests.ProbeConfiguration.class)
class SourceApiTests {

    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("source-api.db") + "?foreign_keys=on");
    }

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

    @Autowired private SourceUnbindingService unbinding;
    @Autowired private SourcePreparationServiceTests.FakeProbe probe;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
        jdbcTemplate.update("DELETE FROM source_membership");
        jdbcTemplate.update("DELETE FROM file_entry");
        jdbcTemplate.update("DELETE FROM scan_run_source");
        jdbcTemplate.update("DELETE FROM working_set_content");
        jdbcTemplate.update("DELETE FROM analysis_record");
        jdbcTemplate.update("DELETE FROM job");
        jdbcTemplate.update("DELETE FROM scan_run");
        jdbcTemplate.update("DELETE FROM working_set");
        jdbcTemplate.update("DELETE FROM content_record");
        jdbcTemplate.update("DELETE FROM source_binding_period");
        jdbcTemplate.update("DELETE FROM source");
        jdbcTemplate.update("DELETE FROM location_context");
        probe.reset();
    }

    @Test
    void registersAndReadsAValidSource() throws Exception {
        String rootPath = absolutePath("photos");

        var registrationResult = register("Photos", rootPath)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Photos"))
                .andExpect(jsonPath("$.rootPath").value(rootPath))
                .andExpect(jsonPath("$.locationRevision").value(0))
                .andExpect(jsonPath("$.preparationState").value("PREPARATION_REQUIRED"))
                .andExpect(jsonPath("$.createdAtMs").isNumber())
                .andExpect(jsonPath("$.updatedAtMs").isNumber())
                .andExpect(jsonPath("$.rootPathKey").doesNotExist())
                .andReturn();

        Source persistedSource = catalogRepository.findAllSources().getFirst();
        SourceResponse response = jsonMapper.readValue(
                registrationResult.getResponse().getContentAsString(), SourceResponse.class);

        assertEquals(persistedSource.id(), response.id());
        assertEquals(rootPath, persistedSource.rootPathKey());
        assertEquals(persistedSource.createdAtMs(), persistedSource.updatedAtMs());
        assertTrue(persistedSource.createdAtMs() > 0);
        assertEquals("/api/sources/" + persistedSource.id(),
                registrationResult.getResponse().getHeader("Location"));

        mockMvc.perform(get("/api/sources/{id}", persistedSource.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persistedSource.id()))
                .andExpect(jsonPath("$.name").value("Photos"))
                .andExpect(jsonPath("$.rootPath").value(rootPath))
                .andExpect(jsonPath("$.preparationState").value("PREPARATION_REQUIRED"))
                .andExpect(jsonPath("$.rootPathKey").doesNotExist());
    }

    @Test
    void returnsNotFoundForAnUnknownSource() throws Exception {
        mockMvc.perform(get("/api/sources/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsAnEmptyArrayWhenNoSourcesAreRegistered() throws Exception {
        mockMvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listsSourcesByAscendingIdAndAllowsDuplicateNamesAndPaths() throws Exception {
        String rootPath = absolutePath("shared");

        register("Duplicate", rootPath).andExpect(status().isCreated());
        register("Duplicate", rootPath).andExpect(status().isCreated());

        var sources = catalogRepository.findAllSources();
        assertEquals(2, sources.size());
        assertNotEquals(sources.get(0).id(), sources.get(1).id());

        mockMvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(sources.get(0).id()))
                .andExpect(jsonPath("$[1].id").value(sources.get(1).id()))
                .andExpect(jsonPath("$[0].name").value("Duplicate"))
                .andExpect(jsonPath("$[1].name").value("Duplicate"))
                .andExpect(jsonPath("$[0].rootPath").value(rootPath))
                .andExpect(jsonPath("$[0].preparationState").value("PREPARATION_REQUIRED"))
                .andExpect(jsonPath("$[1].preparationState").value("PREPARATION_REQUIRED"))
                .andExpect(jsonPath("$[1].rootPath").value(rootPath));
    }

    @Test
    void rejectsABlankName() throws Exception {
        register("   ", absolutePath("photos"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsABlankRootPath() throws Exception {
        register("Photos", "  ")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRelativeAndSyntacticallyInvalidPaths() throws Exception {
        register("Relative", Path.of("relative", "photos").toString())
                .andExpect(status().isBadRequest());
        register("Invalid", "\u0000")
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationDoesNotRequireTheAbsolutePathToExist() throws Exception {
        String nonexistentPath = absolutePath("not-created");

        register("Future location", nonexistentPath)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rootPath").value(nonexistentPath));

        assertNotNull(catalogRepository.findAllSources().getFirst().id());
    }

    @Test
    void preparesSourceAndRepeatedPrepareReturnsReady() throws Exception {
        register("Pictures", "/Users/chris/Pictures").andExpect(status().isCreated());
        long id = catalogRepository.findAllSources().getFirst().id();

        mockMvc.perform(post("/api/sources/{id}/prepare", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preparationState").value("READY"))
                .andExpect(jsonPath("$.rootPath").value("/Users/chris/Pictures"))
                .andExpect(jsonPath("$.boundLocationContextId").doesNotExist())
                .andExpect(jsonPath("$.bindingEvidenceJson").doesNotExist());
        mockMvc.perform(post("/api/sources/{id}/prepare", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preparationState").value("READY"));
        mockMvc.perform(get("/api/sources/{id}", id))
                .andExpect(jsonPath("$.preparationState").value("READY"));
        mockMvc.perform(get("/api/sources"))
                .andExpect(jsonPath("$[0].preparationState").value("READY"));
    }

    @Test
    void prepareUnknownAndPreviouslyUnboundSourcesFailClearly() throws Exception {
        mockMvc.perform(post("/api/sources/{id}/prepare", Long.MAX_VALUE))
                .andExpect(status().isNotFound());

        register("Pictures", "/Users/chris/Pictures").andExpect(status().isCreated());
        long id = catalogRepository.findAllSources().getFirst().id();
        mockMvc.perform(post("/api/sources/{id}/prepare", id)).andExpect(status().isOk());
        var ready = catalogRepository.findSourceById(id).orElseThrow();
        unbinding.unbind(id, ready.locationRevision(),
                Math.max(System.currentTimeMillis(), ready.updatedAtMs()));

        mockMvc.perform(post("/api/sources/{id}/prepare", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CHANGED"));
        mockMvc.perform(get("/api/sources/{id}", id))
                .andExpect(jsonPath("$.preparationState").value("REBIND_REQUIRED"));
    }

    @Test
    void prepareReportsUnavailableAndUnsupportedEvidence() throws Exception {
        register("Pictures", "/Users/chris/Pictures").andExpect(status().isCreated());
        long id = catalogRepository.findAllSources().getFirst().id();
        probe.anchorFailure = ContinuityProbeResult.unavailable();
        mockMvc.perform(post("/api/sources/{id}/prepare", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATH_UNAVAILABLE"));
        probe.anchorFailure = ContinuityProbeResult.unsupported();
        mockMvc.perform(post("/api/sources/{id}/prepare", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROFILE_UNSUPPORTED"));
        mockMvc.perform(get("/api/sources/{id}", id))
                .andExpect(jsonPath("$.preparationState").value("PREPARATION_REQUIRED"));
    }

    private ResultActions register(String name, String rootPath) throws Exception {
        String requestJson = jsonMapper.writeValueAsString(Map.of("name", name, "rootPath", rootPath));
        return mockMvc.perform(post("/api/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson));
    }

    private String absolutePath(String child) {
        return temporaryDirectory.resolve(child).toAbsolutePath().toString();
    }
}
