package io.github.topher6835.mediacompare.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.HostFileSystem;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsNative;
import io.github.topher6835.mediacompare.filesystem.WindowsVolumeProbe;
import io.github.topher6835.mediacompare.location.LocationPath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

class SessionCreationServiceTests {
    @TempDir Path temporary;

    private Path parent() throws IOException { return temporary.toRealPath(); }

    @Test
    void createsOnlyManifestUnderNonemptyParentAndReturnsActualNameAndPath() throws Exception {
        Path parent = parent();
        Files.writeString(parent.resolve("original.jpg"), "original");
        Files.createDirectory(parent.resolve("unrelated"));
        String name = "Voyage été 日本 🎬 — Alice's (2026)!";
        var created = new SessionCreationService().create(parent, name);
        assertEquals(name, created.name());
        assertEquals(parent.resolve(name), created.path());
        assertEquals(created.path(), created.session().root());
        assertEquals(created.path(), Session.open(created.path()).root());
        try (var entries = Files.list(created.path())) {
            assertEquals(List.of(created.session().manifestPath()), entries.toList());
        }
        assertEquals("original", Files.readString(parent.resolve("original.jpg")));
        assertTrue(Files.isDirectory(parent.resolve("unrelated")));
    }

    @Test
    void usesFirstAvailableSuffixAndPreservesFilesAndDirectories() throws Exception {
        Path parent = parent();
        Files.writeString(parent.resolve("Name"), "keep file");
        Files.createDirectory(parent.resolve("Name (001)"));
        Files.writeString(parent.resolve("Name (001)/notes"), "keep notes");
        Files.createDirectory(parent.resolve("Name (003)"));
        var result = new SessionCreationService().create(parent, "Name");
        assertEquals("Name (002)", result.name());
        assertEquals("keep file", Files.readString(parent.resolve("Name")));
        assertEquals("keep notes", Files.readString(parent.resolve("Name (001)/notes")));
        assertFalse(Files.exists(parent.resolve("Name (003)/session.json")));
    }

    @Test
    void linksIncludingDanglingLinksOccupyNamesWithoutFollowingOrChangingTargets() throws Exception {
        Path parent = parent();
        Path target = Files.createDirectory(parent.resolve("target"));
        Files.writeString(target.resolve("notes"), "original");
        link(parent.resolve("Name"), target);
        link(parent.resolve("Name (001)"), parent.resolve("missing"));
        assertEquals("Name (002)", new SessionCreationService().create(parent, "Name").name());
        assertTrue(Files.isSymbolicLink(parent.resolve("Name")));
        assertTrue(Files.isSymbolicLink(parent.resolve("Name (001)")));
        assertEquals("original", Files.readString(target.resolve("notes")));
        assertFalse(Files.exists(target.resolve("session.json")));
        assertFalse(Files.exists(parent.resolve("missing")));
    }

    @Test
    void rejectsInvalidAndReservedNamesBeforeAnyMutation() throws Exception {
        Path parent = parent();
        String[] invalid = { "", " ", "\t", "\u00a0\u2003", ".", "..", "a/b", "a\\b", "a:b",
                "a<b", "a>b", "a\"b", "a|b", "a?b", "a*b", "a\u0000b", "a\u001fb", "a\u007fb",
                "a\u0085b", "Name ", "Name.", "CON", "con.txt", "NUL", "Prn.log", "aux",
                "COM1", "com9.txt", "LPT1", "lpt9.more.txt", "COM¹", "COM².txt", "COM³", "LPT¹.txt",
                "LPT²", "LPT³", "CONIN$", "conout$.txt", "CON .txt", "\ud800", "\udc00",
                "a".repeat(250), "界".repeat(84) };
        SessionCreationService service = new SessionCreationService();
        assertThrows(IllegalArgumentException.class, () -> service.create(parent, null));
        for (String name : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.create(parent, name), name);
        }
        try (var entries = Files.list(parent)) { assertEquals(0, entries.count()); }
        for (String allowed : List.of("COM0", "COM10", "LPT0", "console", "report.v1", "a".repeat(249),
                "界".repeat(83))) {
            assertDoesNotThrow(() -> SessionName.validate(allowed));
        }
    }

    @Test
    void maximumLengthStillAllowsFinalSuffix() throws Exception {
        Path parent = parent();
        String name = "a".repeat(249);
        Files.createDirectory(parent.resolve(name));
        assertEquals(name + " (001)", new SessionCreationService().create(parent, name).name());
    }

    @Test
    void exhaustsExactlyOneThousandNamesWithoutChangingThem() throws Exception {
        Path parent = parent();
        for (int suffix = 0; suffix <= 999; suffix++) {
            Files.createDirectory(parent.resolve(suffix == 0 ? "Name"
                    : String.format(Locale.ROOT, "Name (%03d)", suffix)));
        }
        IOException error = assertThrows(IOException.class,
                () -> new SessionCreationService().create(parent, "Name"));
        assertTrue(error.getMessage().contains("1,000"));
        try (var entries = Files.list(parent)) { assertEquals(1000, entries.count()); }
        assertFalse(Files.exists(parent.resolve("Name (1000)")));
        assertFalse(Files.exists(parent.resolve("Name/session.json")));
    }

    @Test
    void rejectsRelativeMissingFileAndLinkedParentsWithoutCreatingAncestors() throws Exception {
        SessionCreationService service = new SessionCreationService();
        assertThrows(IllegalArgumentException.class, () -> service.create(null, "Name"));
        assertThrows(IllegalArgumentException.class, () -> service.create(Path.of("relative"), "Name"));
        Path missing = parent().resolve("missing/ancestor");
        assertThrows(IOException.class, () -> service.create(missing, "Name"));
        assertFalse(Files.exists(parent().resolve("missing")));
        Path file = Files.writeString(parent().resolve("file"), "keep");
        assertThrows(IOException.class, () -> service.create(file, "Name"));
        assertEquals("keep", Files.readString(file));
        Path alias = parent().resolve("alias");
        link(alias, parent());
        assertThrows(IOException.class, () -> service.create(alias, "Name"));
        assertFalse(Files.exists(parent().resolve("Name")));
    }

    @Test
    void permitsTrustedAncestorAliasButDetectsItsRetargetingBeforeMutation() throws Exception {
        Path parent = parent();
        Path first = Files.createDirectories(parent.resolve("first/selected"));
        Path second = Files.createDirectories(parent.resolve("second/selected"));
        Path alias = parent.resolve("alias");
        link(alias, first.getParent());
        assertEquals(first.resolve("Good"), new SessionCreationService().create(alias.resolve("selected"), "Good").path());
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.BEFORE_CREATE) {
                Files.delete(alias);
                Files.createSymbolicLink(alias, second.getParent());
            }
        });
        assertThrows(IOException.class, () -> service.create(alias.resolve("selected"), "Blocked"));
        assertFalse(Files.exists(first.resolve("Blocked")));
        assertFalse(Files.exists(second.resolve("Blocked")));
    }

    @Test
    void unsupportedOrUncertainStorageAndMissingIdentityFailBeforeCreatingAnything() throws Exception {
        Path parent = parent();
        for (FileSystemProfile profile : List.of(FileSystemProfile.EXFAT, FileSystemProfile.UNKNOWN,
                FileSystemProfile.UNSUPPORTED)) {
            try (var hosts = mockStatic(HostFileSystems.class)) {
                hosts.when(HostFileSystems::current).thenReturn(host(profile));
                assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"));
            }
        }
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            var attributes = mock(BasicFileAttributes.class);
            when(attributes.isDirectory()).thenReturn(true);
            files.when(() -> Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
                    .thenReturn(attributes);
            assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"));
        }
        assertFalse(Files.exists(parent.resolve("Name")));
    }

    @Test
    void permissionFailureIsNotACollisionAndNeverTriesASuffix() throws Exception {
        Path parent = parent();
        Path child = parent.resolve("Name");
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("createDirectory")
                    && child.equals(invocation.getArgument(0))) {
                throw new AccessDeniedException(child.toString());
            }
            return invocation.callRealMethod();
        })) {
            IOException error = assertThrows(IOException.class,
                    () -> new SessionCreationService().create(parent, "Name"));
            assertInstanceOf(AccessDeniedException.class, error.getCause());
            assertTrue(error.getMessage().contains(child.toString()));
        }
        assertFalse(Files.exists(child));
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void unconfirmedCollisionStopsRatherThanTryingAnotherName() throws Exception {
        Path parent = parent();
        Path child = parent.resolve("Name");
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("createDirectory")
                    && child.equals(invocation.getArgument(0))) {
                throw new FileAlreadyExistsException(child.toString());
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"));
        }
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void inaccessibleCollisionEvidenceDoesNotAdvanceSuffix() throws Exception {
        Path parent = parent();
        Path child = Files.writeString(parent.resolve("Name"), "keep");
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("readAttributes")
                    && child.equals(invocation.getArgument(0))) {
                throw new AccessDeniedException(child.toString());
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"));
        }
        assertEquals("keep", Files.readString(child));
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void childOnDifferentStoreIsPreservedButNeverInitialized() throws Exception {
        Path parent = parent();
        Path child = parent.resolve("Name");
        FileStore differentStore = mock(FileStore.class);
        when(differentStore.type()).thenReturn(Files.getFileStore(parent).type());
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("getFileStore")
                    && child.equals(invocation.getArgument(0))) return differentStore;
            return invocation.callRealMethod();
        })) {
            assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"));
        }
        assertTrue(Files.isDirectory(child));
        assertFalse(Files.exists(child.resolve("session.json")));
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void initializationWriteFailurePreservesEmptyDirectoryAndDoesNotRetry() throws Exception {
        Path parent = parent();
        Path child = parent.resolve("Name");
        Path manifest = child.resolve("session.json");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.writeString(eq(manifest), any(CharSequence.class), any(OpenOption[].class)))
                    .thenThrow(new AccessDeniedException(manifest.toString()));
            IOException error = assertThrows(IOException.class,
                    () -> new SessionCreationService().create(parent, "Name"));
            assertInstanceOf(AccessDeniedException.class, error.getCause());
            assertTrue(error.getMessage().contains(child.toString()));
            assertTrue(error.getMessage().contains("not removed"));
        }
        assertTrue(Files.isDirectory(child));
        assertFalse(Files.exists(manifest));
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void unexpectedChildContentsArePreservedAndBlockInitialization() throws Exception {
        Path parent = parent();
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.BEFORE_MANIFEST) {
                Files.writeString(child.resolve("session.json"), "someone else's file");
            }
        });
        assertThrows(IOException.class, () -> service.create(parent, "Name"));
        assertEquals("someone else's file", Files.readString(parent.resolve("Name/session.json")));
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void parentReplacementBeforeCreationIsDetected() throws Exception {
        Path selected = Files.createDirectory(parent().resolve("selected"));
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.BEFORE_CREATE) {
                Files.move(selected, parent().resolve("original"));
                Files.createDirectory(selected);
            }
        });
        assertThrows(IOException.class, () -> service.create(selected, "Name"));
        assertFalse(Files.exists(selected.resolve("Name")));
        assertFalse(Files.exists(parent().resolve("original/Name")));
    }

    @Test
    void parentChangeDuringCollisionDoesNotAdvanceSuffix() throws Exception {
        Path selected = Files.createDirectory(parent().resolve("selected"));
        Files.createDirectory(selected.resolve("Name"));
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.AFTER_COLLISION) {
                Files.move(selected, parent().resolve("original"));
                Files.createDirectory(selected);
            }
        });
        assertThrows(IOException.class, () -> service.create(selected, "Name"));
        assertFalse(Files.exists(selected.resolve("Name (001)")));
        assertTrue(Files.isDirectory(parent().resolve("original/Name")));
    }

    @Test
    void childDisappearanceOrReplacementNeverRecreatesOrInitializesReplacement() throws Exception {
        Path parent = parent();
        for (boolean replace : List.of(false, true)) {
            String name = replace ? "Replaced" : "Disappeared";
            var service = new SessionCreationService((phase, child) -> {
                if (phase == SessionCreationService.Phase.BEFORE_MANIFEST) {
                    Files.move(child, parent.resolve(name + " original"));
                    if (replace) Files.createDirectory(child);
                }
            });
            assertThrows(IOException.class, () -> service.create(parent, name));
            assertEquals(replace, Files.isDirectory(parent.resolve(name)));
            assertTrue(Files.isDirectory(parent.resolve(name + " original")));
            assertFalse(Files.exists(parent.resolve(name + "/session.json")));
            assertFalse(Files.exists(parent.resolve(name + " original/session.json")));
            assertFalse(Files.exists(parent.resolve(name + " (001)")));
        }
    }

    @Test
    void missingParentDuringInitializationNeverRecreatesAncestors() throws Exception {
        Path selected = Files.createDirectory(parent().resolve("selected"));
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.BEFORE_MANIFEST) {
                Files.move(selected, parent().resolve("moved"));
            }
        });
        assertThrows(IOException.class, () -> service.create(selected, "Name"));
        assertFalse(Files.exists(selected));
        assertTrue(Files.isDirectory(parent().resolve("moved/Name")));
        assertFalse(Files.exists(parent().resolve("moved/Name/session.json")));
    }

    @Test
    void parentReplacementAfterCreatePreservesCreatedDirectoryWithoutWritingManifest() throws Exception {
        Path selected = Files.createDirectory(parent().resolve("selected"));
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.AFTER_CREATE) {
                Files.move(selected, parent().resolve("moved"));
                Files.createDirectory(selected);
                Files.createDirectory(selected.resolve("Name"));
            }
        });
        assertThrows(IOException.class, () -> service.create(selected, "Name"));
        assertFalse(Files.exists(selected.resolve("Name/session.json")));
        assertFalse(Files.exists(parent().resolve("moved/Name/session.json")));
    }

    @Test
    void lateFailurePreservesCompletedManifestAndDoesNotTryAnotherName() throws Exception {
        Path parent = parent();
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.AFTER_INITIALIZATION) {
                throw new IOException("injected late observation failure");
            }
        });
        assertThrows(IOException.class, () -> service.create(parent, "Name"));
        assertEquals(parent.resolve("Name"), Session.open(parent.resolve("Name")).root());
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void changedStorageBeforeMutationFailsClosed() throws Exception {
        Path parent = parent();
        FileSystemProfile[] profile = { FileSystemProfile.APFS };
        HostFileSystem host = spy(host(FileSystemProfile.APFS));
        doAnswer(invocation -> profile[0]).when(host).profile(any());
        try (var hosts = mockStatic(HostFileSystems.class)) {
            hosts.when(HostFileSystems::current).thenReturn(host);
            var service = new SessionCreationService((phase, child) -> {
                if (phase == SessionCreationService.Phase.BEFORE_CREATE) profile[0] = FileSystemProfile.EXFAT;
            });
            assertThrows(IOException.class, () -> service.create(parent, "Name"));
        }
        assertFalse(Files.exists(parent.resolve("Name")));
    }

    @Test
    void windowsNativeIdentityChangesFailClosedEvenWhenNioKeyIsUnchanged() throws Exception {
        Path parent = parent();
        HostFileSystem windows = spy(host(FileSystemProfile.NTFS));
        doReturn(true).when(windows).isWindows();
        AtomicInteger generation = new AtomicInteger(1);
        try (var hosts = mockStatic(HostFileSystems.class);
                var nativeAccess = mockStatic(WindowsNtfsNative.class);
                var volumes = windowsVolumes(3)) {
            hosts.when(HostFileSystems::current).thenReturn(windows);
            nativeAccess.when(() -> WindowsNtfsNative.observe(any())).thenAnswer(invocation ->
                    new WindowsNtfsNative.Observation(new WindowsNtfsIdentity("0000000000000001",
                            String.format(Locale.ROOT, "%032x", generation.get())), true, false));
            var service = new SessionCreationService((phase, child) -> {
                if (phase == SessionCreationService.Phase.BEFORE_MANIFEST) generation.incrementAndGet();
            });
            assertThrows(IOException.class, () -> service.create(parent, "Name"));
        }
        assertTrue(Files.isDirectory(parent.resolve("Name")));
        assertFalse(Files.exists(parent.resolve("Name/session.json")));
    }

    @Test
    void childReplacementAfterManifestWriteIsDetectedWithoutDeletingEitherDirectory() throws Exception {
        Path parent = parent();
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.AFTER_INITIALIZATION) {
                Files.move(child, parent.resolve("original"));
                Files.createDirectory(child);
            }
        });
        assertThrows(IOException.class, () -> service.create(parent, "Name"));
        assertTrue(Files.isDirectory(parent.resolve("Name")));
        assertFalse(Files.exists(parent.resolve("Name/session.json")));
        assertEquals(parent.resolve("original"), Session.open(parent.resolve("original")).root());
        assertFalse(Files.exists(parent.resolve("Name (001)")));
    }

    @Test
    void childLinkReplacementIsRejectedWithoutWritingThroughIt() throws Exception {
        Path parent = parent();
        Path target = Files.createDirectory(parent.resolve("target"));
        // Establish fixture capability before entering the service's exception boundary.
        Path probe = parent.resolve("link-probe");
        link(probe, target);
        Files.delete(probe);
        var service = new SessionCreationService((phase, child) -> {
            if (phase == SessionCreationService.Phase.BEFORE_MANIFEST) {
                Files.move(child, parent.resolve("original"));
                Files.createSymbolicLink(child, target);
            }
        });
        assertThrows(IOException.class, () -> service.create(parent, "Name"));
        assertTrue(Files.isSymbolicLink(parent.resolve("Name")));
        assertFalse(Files.exists(target.resolve("session.json")));
        assertFalse(Files.exists(parent.resolve("original/session.json")));
    }

    @Test
    void windowsRejectsMissingConflictingReparseAndCrossVolumeNativeEvidenceBeforeMutation() throws Exception {
        Path parent = parent();
        HostFileSystem windows = spy(host(FileSystemProfile.NTFS));
        doReturn(true).when(windows).isWindows();
        for (String mode : List.of("missing", "not-directory", "reparse", "cross-volume")) {
            try (var hosts = mockStatic(HostFileSystems.class);
                    var nativeAccess = mockStatic(WindowsNtfsNative.class);
                    var volumes = windowsVolumes(3)) {
                hosts.when(HostFileSystems::current).thenReturn(windows);
                nativeAccess.when(() -> WindowsNtfsNative.observe(any())).thenAnswer(invocation -> {
                    Path path = invocation.getArgument(0);
                    String volume = mode.equals("cross-volume") && path.equals(parent)
                            ? "0000000000000002" : "0000000000000001";
                    var identity = mode.equals("missing") ? null : new WindowsNtfsIdentity(volume,
                            "00000000000000000000000000000001");
                    return new WindowsNtfsNative.Observation(identity, !mode.equals("not-directory"), mode.equals("reparse"));
                });
                assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"), mode);
            }
            assertFalse(Files.exists(parent.resolve("Name")));
        }
    }

    @Test
    void windowsRemoteOrUncertainDriveTypeIsRejectedBeforeMutation() throws Exception {
        Path parent = parent();
        HostFileSystem windows = spy(host(FileSystemProfile.NTFS));
        doReturn(true).when(windows).isWindows();
        for (int driveType : List.of(0, 1, 4, 5, 6)) {
            try (var hosts = mockStatic(HostFileSystems.class);
                    var volumes = windowsVolumes(driveType)) {
                hosts.when(HostFileSystems::current).thenReturn(windows);
                assertThrows(IOException.class, () -> new SessionCreationService().create(parent, "Name"));
            }
        }
        assertFalse(Files.exists(parent.resolve("Name")));
    }

    @Test
    void boundedErrorDoesNotExposeProviderMessage() throws Exception {
        Path parent = parent();
        var service = new SessionCreationService((phase, child) -> {
            throw new IOException("provider diagnostic " + "x".repeat(5000));
        });
        IOException error = assertThrows(IOException.class, () -> service.create(parent, "Name"));
        assertTrue(error.getMessage().length() < 800);
        assertFalse(error.getMessage().contains("provider diagnostic"));
    }

    private static MockedConstruction<WindowsVolumeProbe> windowsVolumes(int driveType) {
        return mockConstruction(WindowsVolumeProbe.class, (probe, context) ->
                when(probe.probe(any())).thenAnswer(invocation -> {
                    Path path = invocation.getArgument(0);
                    var volume = new WindowsVolumeProbe.NativeVolume("NTFS", "00000001", null, driveType,
                            path.getRoot().toString(), null, List.of(), false);
                    return new WindowsVolumeProbe.Result(FileSystemProfile.NTFS, "NTFS", volume, false);
                }));
    }

    private static HostFileSystem host(FileSystemProfile profile) {
        return new HostFileSystem() {
            @Override public String pathText(LocationPath location) { throw new UnsupportedOperationException(); }
            @Override public boolean supportsProfile(FileSystemProfile candidate) {
                return candidate == FileSystemProfile.APFS || candidate == FileSystemProfile.NTFS;
            }
            @Override public FileSystemProfile profile(Path path) { return profile; }
            @Override public boolean unsafeElement(Path path, BasicFileAttributes attributes) {
                return attributes.isSymbolicLink() || attributes.isOther();
            }
        };
    }

    private static void link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException failure) {
            Assumptions.assumeTrue(false, "Symbolic-link fixture unavailable: " + failure);
        }
    }
}
