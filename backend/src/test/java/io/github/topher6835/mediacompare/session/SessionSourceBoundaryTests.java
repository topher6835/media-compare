package io.github.topher6835.mediacompare.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSourceBoundaryTests {
    @TempDir Path directory;

    @Test
    void canonicalizesExistingAncestorsOfMissingSourcePaths() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("parent")).toRealPath();
        Path session = Files.createDirectory(parent.resolve("session")).toRealPath();
        Path alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, parent);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Host cannot create a symbolic link");
        }
        var boundary = new SessionSourceBoundary(session);
        assertThrows(SourceWorkspaceOverlapException.class,
                () -> boundary.requireSeparate(alias.resolve("session/new-photos").toString()));
        assertThrows(SourceWorkspaceOverlapException.class,
                () -> boundary.requireSeparate(alias.resolve("session").toString()));
        assertDoesNotThrow(() -> boundary.requireSeparate(alias.resolve("sibling/new-photos").toString()));
    }
}
