package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.matching.FinderRevealProcess;
import io.github.topher6835.mediacompare.matching.RevealCatalog;
import io.github.topher6835.mediacompare.matching.RevealFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest
@AutoConfigureMockMvc
@Import({IndexingRunApiTests.Hooks.class, CleanupPreflightApiTests.Hooks.class, RevealFileApiTests.Hooks.class})
class RevealFileApiTests extends V3ApiTestBase {
    @Autowired RevealFileService service;
    @Autowired RevealCatalog revealCatalog;
    @Autowired CleanupPreflightApiTests.TestValidator validator;
    @Autowired TestFinder finder;
    Source source;
    Path file;
    long fileId;

    @BeforeEach
    void prepare() throws Exception {
        validator.reset();
        finder.calls.set(0);
        finder.succeeds = true;
        source = source("reveal");
        file = Path.of(source.rootPath()).resolve("example.heic");
        Files.writeString(file, "picture");
        completed(source);
        fileId = jdbc.queryForObject("SELECT file_entry_id FROM source_membership WHERE relative_path = 'example.heic'", Long.class);
    }

    @Test
    void currentFileRevealsByIdWithoutChangingCatalogOrMedia() throws Exception {
        mvc.perform(post(url()).header("X-Media-Compare-Reveal", "1")).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(1, finder.calls.get());
        assertEquals(file, finder.path);
        assertEquals("picture", Files.readString(file));
        assertEquals(0, validator.hashReads.get());
    }

    @Test
    void requestBodyCannotSupplyPathOrCommand() throws Exception {
        mvc.perform(post(url()).header("X-Media-Compare-Reveal", "1").contentType("application/json")
                .content("{\"path\":\"/tmp/other\",\"executable\":\"/bin/sh\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BODY_NOT_ALLOWED"));
        assertEquals(0, finder.calls.get());
    }

    @Test
    void ordinaryCrossOriginFormPostCannotTriggerReveal() throws Exception {
        mvc.perform(post(url())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTION_HEADER_REQUIRED"));
        assertEquals(0, finder.calls.get());
    }

    @Test
    void absentFileEntryIs404() throws Exception {
        mvc.perform(post("/api/media-library/items/999999/reveal").header("X-Media-Compare-Reveal", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_ENTRY_NOT_FOUND"));
        assertEquals(0, finder.calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"observed_file_entry_revision", "observed_source_location_revision",
            "observed_location_context_revision"})
    void staleObservedRevisionsAreRejected(String column) throws Exception {
        jdbc.update("UPDATE source_membership SET " + column + " = " + column + " + 1 WHERE file_entry_id = ?", fileId);
        stale();
    }

    @Test void missingMembershipIsRejected() throws Exception {
        jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE file_entry_id = ?", fileId);
        stale();
    }

    @Test void retiredMembershipIsRejected() throws Exception {
        jdbc.update("UPDATE source_membership SET applicability_status = 'RETIRED' WHERE file_entry_id = ?", fileId);
        stale();
    }

    @Test void missingContentIsRejected() throws Exception {
        jdbc.update("UPDATE file_entry SET current_content_id = NULL WHERE id = ?", fileId);
        stale();
    }

    @Test void advancedFileEntryObservationIsRejected() throws Exception {
        jdbc.update("UPDATE file_entry SET observation_revision = observation_revision + 1 WHERE id = ?", fileId);
        stale();
    }

    @Test void advancedSourceRevisionIsRejected() throws Exception {
        jdbc.update("UPDATE source SET location_revision = location_revision + 1 WHERE id = ?", source.id());
        stale();
    }

    @Test void advancedContextRevisionIsRejected() throws Exception {
        jdbc.update("UPDATE location_context SET revision = revision + 1 WHERE id = ?", source.boundLocationContextId());
        stale();
    }

    @Test void unresolvedFileEntryIsRejected() throws Exception {
        jdbc.update("""
                UPDATE file_entry SET location_identity_status = 'UNRESOLVED',
                    location_context_id = NULL, location_path = NULL, location_key = NULL WHERE id = ?
                """, fileId);
        stale();
    }

    @Test void sourceNoLongerBoundIsRejected() throws Exception {
        jdbc.update("UPDATE source SET bound_location_context_id = NULL, binding_evidence_json = NULL WHERE id = ?", source.id());
        stale();
    }

    @Test void inactiveContextIsRejected() throws Exception {
        jdbc.update("UPDATE location_context SET lifecycle_status = 'RETIRED' WHERE id = ?", source.boundLocationContextId());
        stale();
    }

    @Test void unacceptedContextIsRejected() throws Exception {
        jdbc.update("UPDATE location_context SET continuity_status = 'REVIEW_REQUIRED', continuity_evidence_json = NULL WHERE id = ?",
                source.boundLocationContextId());
        stale();
    }

    @Test void incoherentStructuredPathIsRejected() throws Exception {
        jdbc.update("UPDATE file_entry SET location_key = location_key || 'changed' WHERE id = ?", fileId);
        stale();
    }

    @Test void freshApfsContinuityFailureIsRejected() throws Exception {
        validator.host.mode = "mismatch";
        stale();
    }

    @Test void fileMissingIsGone() throws Exception {
        Files.delete(file);
        changed();
    }

    @Test void nonRegularFileIsRejected() throws Exception {
        Files.delete(file);
        Files.createDirectory(file);
        stale();
    }

    @Test void finalSymlinkIsRejected() throws Exception {
        Files.delete(file);
        Path other = file.resolveSibling("other.heic");
        Files.writeString(other, "picture");
        Files.createSymbolicLink(file, other);
        stale();
    }

    @Test void ancestorSymlinkIsRejected() throws Exception {
        Path root = Path.of(source.rootPath());
        Path moved = root.resolveSibling("reveal-moved");
        Files.move(root, moved);
        Files.createSymbolicLink(root, moved);
        stale();
    }

    @Test void changedSizeIsGone() throws Exception {
        Files.writeString(file, "more bytes");
        changed();
    }

    @Test void changedMtimeIsGone() throws Exception {
        Files.setLastModifiedTime(file, FileTime.from(Files.getLastModifiedTime(file).toInstant().plusSeconds(2)));
        changed();
    }

    @Test void catalogChangeAfterFileValidationIsRejected() throws Exception {
        validator.afterValidation = () -> jdbc.update(
                "UPDATE source_membership SET membership_revision = membership_revision + 1 WHERE file_entry_id = ?", fileId);
        stale();
    }

    @Test void unsupportedHostAndFinderFailureAreBounded() throws Exception {
        var unsupported = new RevealFileService(revealCatalog, validator, finder, () -> false);
        assertEquals(RevealFileService.Result.UNSUPPORTED_HOST, unsupported.reveal(fileId));
        assertEquals(0, finder.calls.get());
        finder.succeeds = false;
        mvc.perform(post(url()).header("X-Media-Compare-Reveal", "1")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("FINDER_FAILED"));
        assertEquals(1, finder.calls.get());
    }

    private String url() { return "/api/media-library/items/" + fileId + "/reveal"; }

    private void stale() throws Exception {
        mvc.perform(post(url()).header("X-Media-Compare-Reveal", "1")).andExpect(status().isConflict());
        assertEquals(0, finder.calls.get());
    }

    private void changed() throws Exception {
        mvc.perform(post(url()).header("X-Media-Compare-Reveal", "1")).andExpect(status().isGone());
        assertEquals(0, finder.calls.get());
    }

    @TestConfiguration
    static class Hooks {
        @Bean @Primary TestFinder finder() { return new TestFinder(); }
    }

    static class TestFinder extends FinderRevealProcess {
        final AtomicInteger calls = new AtomicInteger();
        Path path;
        boolean succeeds = true;
        @Override public boolean reveal(Path validatedFile) {
            path = validatedFile;
            calls.incrementAndGet();
            return succeeds;
        }
    }
}
