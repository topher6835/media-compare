package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.topher6835.mediacompare.session.Session;
import io.github.topher6835.mediacompare.web.RegisterSourceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class SessionSourceIsolationTests {
    @TempDir static Path directory;
    private static Session session;

    @DynamicPropertySource
    static void selectedSession(DynamicPropertyRegistry properties) {
        properties.add("media-compare.session-root", () -> selected().root().toString());
    }

    private static synchronized Session selected() {
        if (session == null) {
            try {
                session = Session.create(directory.resolve("media"));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return session;
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void rejectsEqualParentAndChildSourceRoots() throws Exception {
        Path root = selected().root();
        rejected(root);
        rejected(root.getParent());
        rejected(root.resolve("photos"));
        rejected(root.resolve("cache/../photos"));
    }

    @Test
    void acceptsSiblingsMisleadingPrefixesAndUnrelatedMissingSources() throws Exception {
        Path root = selected().root();
        accepted(root.resolveSibling("sibling"));
        accepted(root.resolveSibling("media-more"));
        Path unrelated = directory.resolveSibling("unrelated-source");
        assertFalse(Files.exists(unrelated));
        accepted(unrelated);
    }

    private void rejected(Path path) throws Exception {
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new RegisterSourceRequest("Photos", path.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_OVERLAPS_SESSION"));
    }

    private void accepted(Path path) throws Exception {
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new RegisterSourceRequest("Photos", path.toString()))))
                .andExpect(status().isCreated());
    }
}
