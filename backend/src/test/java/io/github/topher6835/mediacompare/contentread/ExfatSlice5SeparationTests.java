package io.github.topher6835.mediacompare.contentread;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import io.github.topher6835.mediacompare.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ExfatSlice5SeparationTests {
    @TempDir Path directory;
    @Test @EnabledOnOs(OS.MAC)
    void validatesSelectedSessionAgainAndRejectsChangedAliasOrManifest() throws Exception {
        Path session = Session.create(directory.resolve("session")).root();
        Path source = Files.createDirectory(directory.resolve("source")).toRealPath();
        var boundary = new SessionSourceBoundary(session);
        boundary.requireFreshSeparate(source.toString());
        assertThrows(SourceWorkspaceOverlapException.class, () -> boundary.requireFreshSeparate(session.toString()));
        Files.writeString(session.resolve("session.json"), "{}");
        assertThrows(java.io.IOException.class, () -> boundary.requireFreshSeparate(source.toString()));
    }
    @Test @EnabledOnOs(OS.MAC)
    void rejectsSourceSymlinkAndMissingOriginalRootInsteadOfUsingStoredSeparation() throws Exception {
        Path session = Session.create(directory.resolve("session")).root();
        Path source = Files.createDirectory(directory.resolve("source")).toRealPath();
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), source);
        var boundary = new SessionSourceBoundary(session);
        assertThrows(SourceWorkspaceOverlapException.class, () -> boundary.requireFreshSeparate(alias.toString()));
        Files.delete(source);
        assertThrows(SourceWorkspaceOverlapException.class, () -> boundary.requireFreshSeparate(source.toString()));
    }
}
