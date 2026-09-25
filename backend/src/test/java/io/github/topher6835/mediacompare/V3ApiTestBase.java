package io.github.topher6835.mediacompare;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.scan.IndexingRunDetails;
import io.github.topher6835.mediacompare.scan.IndexingRunService;
import io.github.topher6835.mediacompare.scan.V3TestHost;

/** Shared deterministic APFS fixture for the API tests that now exercise v3 writes. */
abstract class V3ApiTestBase {
    @TempDir Path directory;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogRepository catalog;
    @Autowired IndexingRunService starts;
    @Autowired IndexingRunApiTests.ControlledExecutor executor;

    @BeforeEach
    void clear() {
        executor.reset();
        for (String table : List.of("content_hash", "job_stage", "source_membership", "file_entry",
                "scan_run_source", "working_set_content", "analysis_record", "job", "scan_run",
                "working_set", "content_record", "source", "location_context")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    Source source(String name) throws Exception {
        Path root = Files.createDirectory(directory.resolve(name));
        return V3TestHost.boundSource(catalog, jdbc, root, name);
    }

    IndexingRunDetails pending(Source... sources) {
        return starts.start(UUID.randomUUID().toString(),
                java.util.Arrays.stream(sources).map(Source::id).toList()).run();
    }

    IndexingRunDetails completed(Source... sources) throws Exception {
        IndexingRunDetails accepted = pending(sources);
        executor.runAccepted();
        return accepted;
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
