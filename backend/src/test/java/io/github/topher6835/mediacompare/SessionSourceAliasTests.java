package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.topher6835.mediacompare.session.Session;
import io.github.topher6835.mediacompare.web.RegisterSourceRequest;
import io.github.topher6835.mediacompare.web.SourceController;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class SessionSourceAliasTests {
    @TempDir Path directory;

    @Test
    void ancestorAliasCannotHideSourceContainingCanonicalSession() throws Exception {
        Path realParent = Files.createDirectory(directory.resolve("real-parent")).toRealPath();
        Session session = Session.create(realParent.resolve("my-session"));
        Path aliasParent = directory.resolve("parent-alias");
        try {
            Files.createSymbolicLink(aliasParent, realParent);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Host cannot create symbolic-link test fixture: " + exception);
        }
        Path selectedThroughAlias = aliasParent.resolve("my-session");
        assertEquals(session.root(), Session.open(selectedThroughAlias).root());

        try (var context = new SpringApplicationBuilder(MediaCompareApplication.class)
                .web(WebApplicationType.NONE)
                .run("--media-compare.session-root=" + selectedThroughAlias,
                        "--logging.level.root=ERROR")) {
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(SourceController.class)).build();
            var request = new RegisterSourceRequest("Containing Source", realParent.toString());
            var json = context.getBean(JsonMapper.class).writeValueAsString(request);
            mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SOURCE_OVERLAPS_SESSION"));
        }
    }
}
