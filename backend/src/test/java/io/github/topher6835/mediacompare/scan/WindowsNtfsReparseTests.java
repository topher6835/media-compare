package io.github.topher6835.mediacompare.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.location.*;
import io.github.topher6835.mediacompare.scan.authority.*;
import io.github.topher6835.mediacompare.session.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsNtfsReparseTests {
    @TempDir(factory = CheckoutTempDirFactory.class) Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"file-symlink", "directory-symlink", "junction"})
    void nativeInspectionPreparationAndTraversalRejectReparsePoints(String kind) throws Exception {
        Path root = Files.createDirectory(directory.resolve("source"));
        Path target = Files.createDirectory(directory.resolve("outside"));
        Path outsideFile = Files.writeString(target.resolve("outside.jpg"), "outside bytes");
        Path alias = root.resolve("alias");
        createAlias(kind, alias, kind.equals("file-symlink") ? outsideFile : target);
        // Remove the alias explicitly before TempDir cleanup; never walk through a junction during cleanup.
        try {
            assertTrue(WindowsNtfsNative.observe(alias).reparsePoint());
            assertNull(WindowsNtfsNative.observe(alias).identity());
            assertEquals(HostFileStatus.UNSAFE_PATH, WindowsNtfsPathInspector.inspect(location(alias),
                    !kind.equals("file-symlink")).status());
            if (!kind.equals("file-symlink")) {
                assertEquals(HostFileStatus.UNSAFE_PATH,
                        WindowsNtfsPathInspector.inspect(location(alias.resolve("outside.jpg")), false).status());
            }
            try (var app = new SpringApplicationBuilder(MediaCompareApplication.class).web(WebApplicationType.NONE)
                    .run("--spring.datasource.url=jdbc:sqlite:" + directory.resolve("catalog.db")
                            + "?foreign_keys=on", "--logging.level.root=ERROR")) {
                var sources = app.getBean(SourceService.class);
                var preparation = app.getBean(SourcePreparationService.class);
                var aliasSource = sources.register("Alias", alias.toString());
                assertThrows(SourcePreparationException.class, () -> preparation.prepare(aliasSource.id()));
                var source = preparation.prepare(sources.register("Normal root", root.toString()).id());
                var authority = app.getBean(Version3AuthorityCapture.class).capture(source.id()).value().orElseThrow();
                var observations = new ArrayList<ResolvedFileCandidate>();
                var issue = app.getBean(Version3DiscoveryWalker.class).walk(root, authority, observations::add);
                assertNotEquals(TraversalCompletion.Issue.COMPLETE, issue);
                assertTrue(observations.isEmpty(), "Reparse targets must never become trusted candidates");
            }
            assertEquals("outside bytes", Files.readString(outsideFile));
        } finally {
            Files.delete(alias);
        }
    }

    @Test
    void sessionOverlapRejectsCaseAndJunctionAliasesIncludingMissingLeaves() throws Exception {
        Path session = Session.create(directory.resolve("Session")).root();
        var boundary = new SessionSourceBoundary(session);
        assertThrows(SourceWorkspaceOverlapException.class, () -> boundary.requireSeparate(session.toString()));
        assertThrows(SourceWorkspaceOverlapException.class, () -> boundary.requireSeparate(directory.toString()));
        assertThrows(SourceWorkspaceOverlapException.class,
                () -> boundary.requireSeparate(session.resolve("missing").toString()));
        assertThrows(SourceWorkspaceOverlapException.class,
                () -> boundary.requireSeparate(session.toString().toUpperCase(Locale.ROOT)));
        Path alias = directory.resolve("session-alias");
        createAlias("junction", alias, session);
        try {
            assertThrows(SourceWorkspaceOverlapException.class, () -> boundary.requireSeparate(alias.toString()));
            assertThrows(SourceWorkspaceOverlapException.class,
                    () -> boundary.requireSeparate(alias.resolve("missing").toString()));
            boundary.requireSeparate(directory.resolve("separate-source").toString());
        } finally {
            Files.delete(alias);
        }
        assertTrue(Files.isRegularFile(session.resolve("session.json")));
    }

    private static LocationPath location(Path path) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, path.toString());
    }

    private static void createAlias(String kind, Path alias, Path target) throws Exception {
        if (kind.equals("junction")) {
            var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    alias.toString(), target.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction creation timed out");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset()));
        } else {
            try {
                Files.createSymbolicLink(alias, target);
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                Assumptions.abort("Windows symbolic-link creation unavailable: " + exception.getMessage());
            }
        }
    }
}
