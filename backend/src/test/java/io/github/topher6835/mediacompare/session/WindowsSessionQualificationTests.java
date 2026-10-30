package io.github.topher6835.mediacompare.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsNative;
import io.github.topher6835.mediacompare.filesystem.WindowsVolumeProbe;
import io.github.topher6835.mediacompare.management.CatalogProcessSupervisor;
import io.github.topher6835.mediacompare.management.CatalogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Opt-in native fixtures, retained for inspection; no browser qualification is implied. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "media.compare.windows.qualification", matches = "true")
class WindowsSessionQualificationTests {
    @TempDir(cleanup = CleanupMode.NEVER) Path temporary;

    @BeforeEach void requireActualFixedNtfs() throws Exception {
        var volume = new WindowsVolumeProbe().probe(temporary);
        assertEquals("NTFS", volume.nioType());
        assertEquals(3, volume.nativeVolume().driveType());
        assertEquals(temporary.getRoot().toString(), volume.nativeVolume().volumeRoot());
        assertEquals("C:\\", temporary.getRoot().toString());
        System.out.println("Retained Windows fixture: " + temporary + "; volume=" + volume);
    }

    @Test void nativeNamesCollisionsAndManifestPreserveOriginals() throws Exception {
        String name = "Café 日本 + # % (2026)!";
        Files.writeString(temporary.resolve(name), "original file");
        Path occupied = Files.createDirectory(temporary.resolve(name + " (001)"));
        Files.writeString(occupied.resolve("notes.txt"), "original directory");
        var created = new SessionCreationService().create(temporary, name);
        assertEquals(name + " (002)", created.name());
        assertEquals(temporary.resolve(created.name()).toRealPath(), created.path());
        assertEquals(created.path(), Session.open(created.path()).root());
        assertFalse(Files.exists(created.session().catalogPath()));
        assertEquals("original file", Files.readString(temporary.resolve(name)));
        assertEquals("original directory", Files.readString(occupied.resolve("notes.txt")));
        Files.writeString(temporary.resolve("CaseName"), "case sentinel");
        assertEquals("casename (001)", new SessionCreationService().create(temporary, "casename").name());
        assertEquals("case sentinel", Files.readString(temporary.resolve("CaseName")));
        var drive = WindowsNtfsNative.observe(temporary.getRoot()).identity();
        var parent = WindowsNtfsNative.observe(temporary).identity();
        var child = WindowsNtfsNative.observe(created.path()).identity();
        assertEquals(drive.volumeSerial(), child.volumeSerial());
        assertNotEquals(parent.fileId(), child.fileId());
        System.out.println("Native identities: drive=" + drive + "; parent=" + parent + "; child=" + child);
    }

    @Test void selectedParentAndOwnedChildJunctionsAreRejectedAndCollisionIsPreserved() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path sentinel = Files.writeString(target.resolve("sentinel.txt"), "keep");
        Path alias = temporary.resolve("selected parent");
        junction(alias, target);
        try {
            assertTrue(WindowsNtfsNative.observe(alias).reparsePoint());
            assertThrows(IOException.class, () -> new SessionCreationService().create(alias, "Forbidden"));
            assertFalse(Files.exists(target.resolve("Forbidden")));
            assertEquals("selected parent (001)",
                    new SessionCreationService().create(temporary, "selected parent").name());
            assertEquals("keep", Files.readString(sentinel));
        } finally { Files.delete(alias); } // Delete only the junction entry, never traverse its target.
        Session session = Session.create(temporary.resolve("Session"));
        Path cache = session.root().resolve("cache");
        junction(cache, target);
        try { assertThrows(IOException.class, () -> Session.open(session.root())); }
        finally { Files.delete(cache); }
        assertEquals("keep", Files.readString(sentinel));
    }

    @Test void ancestorJunctionRefusalPreservesBothTargets() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("first/parent"));
        Path second = Files.createDirectories(temporary.resolve("second/parent"));
        Path alias = temporary.resolve("ancestor");
        junction(alias, first.getParent());
        try {
            assertThrows(IOException.class,
                    () -> new SessionCreationService().create(alias.resolve("parent"), "Refused"));
            assertFalse(Files.exists(first.resolve("Refused")));
        } finally { Files.delete(alias); }
        junction(alias, second.getParent());
        try {
            assertThrows(IOException.class,
                    () -> new SessionCreationService().create(alias.resolve("parent"), "Retargeted"));
            assertFalse(Files.exists(first.resolve("Retargeted")));
            assertFalse(Files.exists(second.resolve("Retargeted")));
        } finally { Files.delete(alias); }
        System.out.println("Observed conservative ancestor-junction refusal; alias support remains undecided.");
    }

    @Test void deniedParentAndManifestWritesPreserveAttemptedLocationsWithoutSuffixReplay() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("ACL parent"));
        Path sentinel = Files.writeString(parent.resolve("sentinel.txt"), "unchanged");
        var parentAcl = Files.getFileAttributeView(parent, AclFileAttributeView.class);
        var original = parentAcl.getAcl();
        try {
            deny(parentAcl, AclEntryPermission.ADD_SUBDIRECTORY);
            assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Denied"));
            assertFalse(Files.exists(parent.resolve("Denied")));
            assertFalse(Files.exists(parent.resolve("Denied (001)")));
        } finally { parentAcl.setAcl(original); }
        assertEquals("unchanged", Files.readString(sentinel));
        var saved = new ArrayList<AclEntry>();
        Path attempted = parent.resolve("Manifest denied");
        try {
            var service = new SessionCreationService((phase, child) -> {
                if (phase == SessionCreationService.Phase.BEFORE_MANIFEST) {
                    var acl = Files.getFileAttributeView(child, AclFileAttributeView.class);
                    saved.addAll(acl.getAcl());
                    deny(acl, AclEntryPermission.ADD_FILE);
                }
            });
            var failure = assertThrows(SessionCreationService.CreationFailure.class,
                    () -> service.create(parent, "Manifest denied"));
            assertTrue(failure.directoryCreated());
            assertTrue(Files.isDirectory(attempted));
            assertFalse(Files.exists(attempted.resolve("session.json")));
            assertFalse(Files.exists(parent.resolve("Manifest denied (001)")));
        } finally {
            if (!saved.isEmpty()) Files.getFileAttributeView(attempted, AclFileAttributeView.class).setAcl(saved);
        }
        assertEquals("unchanged", Files.readString(sentinel));
        assertNotNull(new SessionCreationService().create(parent, "After ACL restoration"));
        System.out.println("Fixture-only DENY ADD_SUBDIRECTORY / ADD_FILE ACLs restored exactly.");
    }

    @Test void portableLimitsAndLongPathFailuresPreserveLocationsWithoutReplay() throws Exception {
        var service = new SessionCreationService();
        for (String invalid : List.of("CON", "nul.txt", "LPT¹.log", "Name ", "Name.",
                "a".repeat(250), "界".repeat(84))) {
            assertThrows(IllegalArgumentException.class, () -> service.create(temporary, invalid));
        }
        String limit = "a".repeat(249);
        Files.createDirectory(temporary.resolve(limit));
        var componentFailure = assertThrows(SessionCreationService.CreationFailure.class,
                () -> service.create(temporary, limit));
        assertTrue(componentFailure.directoryCreated());
        assertTrue(Files.isDirectory(temporary.resolve(limit + " (001)")));
        assertFalse(Files.exists(temporary.resolve(limit + " (001)/session.json")));
        assertFalse(Files.exists(temporary.resolve(limit + " (002)")));
        assertEquals("界".repeat(83), service.create(temporary, "界".repeat(83)).name());
        Path deep = temporary;
        for (int i = 0; i < 8; i++) deep = Files.createDirectory(deep.resolve("deep-" + i + "-" + "x".repeat(40)));
        Path selected = deep;
        assertTrue(selected.toString().length() > 400);
        var pathFailure = assertThrows(SessionCreationService.CreationFailure.class,
                () -> service.create(selected, "Long path café 日本"));
        assertFalse(pathFailure.directoryCreated());
        assertFalse(Files.exists(selected.resolve("Long path café 日本")));
        System.out.println("Long parent path units=" + selected.toString().length()
                + "; native adapter rejects it; no child created. Component-limit partial directory retained.");
    }

    @Test void unsupportedPathSyntaxAndUnavailableParentAreRejected() throws Exception {
        var host = HostFileSystems.current();
        assertThrows(IOException.class,
                () -> host.requireSessionStorage(Path.of("\\\\unavailable-test-host\\share\\session")));
        assertThrows(IOException.class,
                () -> new SessionCreationService().create(temporary.resolve("absent"), "Child"));
        assertFalse(Files.exists(temporary.resolve("absent")));
    }

    @Test void extendedPrefixNormalizationRetainsTheSameSupportedDirectoryIdentity() throws Exception {
        String extended = "\\\\?\\" + temporary;
        Path parsed = Path.of(extended);
        System.out.println("Extended input=" + extended + "; parsed=" + parsed);
        assertEquals(temporary, parsed);
        HostFileSystems.current().requireSessionStorage(parsed);
        assertEquals(WindowsNtfsNative.observe(temporary), WindowsNtfsNative.observe(parsed));
        System.out.println("Observed Java prefix normalization only; raw-input syntax policy remains undecided.");
    }

    @Test void realChildShutdownReleasesSqliteAndOwnershipForMoveAndReopen() throws Exception {
        Session session = Session.create(temporary.resolve("Session with spaces cafe"));
        String firstGeneration;
        try (var catalog = new CatalogProcessSupervisor()) {
            catalog.start(session.root());
            awaitRunning(catalog);
            firstGeneration = catalog.catalogId();
            assertThrows(IllegalStateException.class, () -> catalog.start(session.root()));
            assertTrue(Files.isRegularFile(session.catalogPath()));
            try (var database = java.sql.DriverManager.getConnection(session.catalogJdbcUrl());
                    var statement = database.createStatement()) {
                statement.execute("INSERT INTO working_set (name, created_at_ms, updated_at_ms) "
                        + "VALUES ('preserved café 日本', 1, 1)");
            }
            ProcessHandle.current().children().filter(p -> p.info().commandLine().orElse("").contains("catalog-child"))
                    .forEach(p -> System.out.println("Test child pid=" + p.pid() + "; parent="
                            + p.parent().orElseThrow().pid() + "; executable=" + p.info().command().orElse("unknown")));
        }
        Path moved = Files.move(session.root(), temporary.resolve("Moved Session cafe"));
        // Windows permits directory rename with some open file handles. Moving each file is stronger evidence.
        for (String file : List.of("catalog.db", "catalog.db.lock")) {
            Path original = moved.resolve(file), renamed = moved.resolve(file + ".rename-probe");
            Files.move(original, renamed);
            Files.move(renamed, original);
        }
        try (var catalog = new CatalogProcessSupervisor()) {
            catalog.start(moved);
            awaitRunning(catalog);
            assertNotEquals(firstGeneration, catalog.catalogId());
        }
        try (var database = java.sql.DriverManager.getConnection(Session.open(moved).catalogJdbcUrl());
                var statement = database.createStatement();
                var rows = statement.executeQuery("SELECT name FROM working_set WHERE created_at_ms = 1")) {
            assertTrue(rows.next());
            assertEquals("preserved café 日本", rows.getString(1));
        }
        System.out.println("Real child STOP/exit, database+lock rename and new-generation reopen passed: " + moved);
    }

    @ParameterizedTest
    @ValueSource(strings = { "ASCII", "Session with spaces", "Café 日本 🎬 + # % ! & (2026)" })
    void packagedSessionStartsRealChildWithDiagnosticOutputRetained(String name) throws Exception {
        Session session = Session.create(temporary.resolve(name));
        String token = java.util.UUID.randomUUID().toString();
        Path log = temporary.resolve("child-diagnostic.log");
        Path jar = Files.copy(Path.of("target/media-compare-0.0.1-SNAPSHOT.jar"),
                temporary.resolve("Application with spaces.jar"));
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-jar", jar.toString(), "catalog-child", token,
                "session-path-utf8-v1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        session.root().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        System.out.println("Diagnostic child pid=" + child.pid() + "; parent="
                + child.toHandle().parent().orElseThrow().pid() + "; Session=" + session.root() + "; log=" + log);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                // Read ASCII control frames without assuming console encoding or complete UTF-8 writes.
                String output = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.ISO_8859_1);
                if (output.contains("MC_CATALOG " + token + " READY ")) { ready = true; break; }
                if (!child.isAlive()) break;
                Thread.sleep(50);
            }
            assertTrue(ready, () -> "Packaged child failed; see " + log);
        } finally {
            child.getOutputStream().close(); // EOF requests ordinary context cleanup, including on assertion failure.
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Test child must actually exit after EOF: " + child.pid());
        }
        assertEquals(0, child.exitValue());
    }

    private static void deny(AclFileAttributeView view, AclEntryPermission permission) throws IOException {
        var entries = new ArrayList<>(view.getAcl());
        entries.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.getOwner())
                .setPermissions(permission).build());
        view.setAcl(entries);
    }

    private static void junction(Path alias, Path target) throws Exception {
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction creation timed out");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    private static void awaitRunning(CatalogProcessSupervisor catalog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            var status = catalog.status();
            if (status.state() == CatalogStatus.State.RUNNING && status.processAlive()) return;
            assertNotEquals(CatalogStatus.State.FAILED, status.state(), status.toString());
            Thread.sleep(50);
        }
        fail("Real catalog did not become ready: " + catalog.status());
    }
}
