package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import org.junit.jupiter.api.Test;

class WindowsHostFileSystemTests {
    private static final String GUID = "\\\\?\\Volume{11111111-2222-3333-4444-555555555555}\\";

    @Test
    void freshDispatchDelegatesNtfsButDoesNotFallBackAfterItsFailure() throws Exception {
        var probe = new FakeProbe();
        var ntfs = new FakeNtfs();
        var host = new WindowsHostFileSystem(probe, ntfs, new WindowsExfatHostFileSystem());
        LocationPath path = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "C:\\Photos\\A.jpg");
        assertEquals(HostFileStatus.ESTABLISHED, host.inspect(path, 1, 1L, 0).status());
        ntfs.failure = new IOException("NTFS identity failed");
        assertSame(ntfs.failure, assertThrows(IOException.class, () -> host.inspect(path, 1, 1L, 0)));
        assertEquals(2, ntfs.inspections);
        probe.profile = FileSystemProfile.EXFAT;
        assertEquals(HostFileStatus.UNVERIFIABLE, host.inspect(path, 1, 1L, 0).status());
        assertThrows(WindowsFileAccessException.class, () -> host.unsafeElement(Path.of("C:/Photos"), null));
        assertEquals(2, ntfs.inspections);
        assertEquals(4, probe.calls);
        assertFalse(host.supportsProfile(FileSystemProfile.EXFAT));
        probe.profile = FileSystemProfile.UNSUPPORTED;
        assertEquals(HostFileStatus.UNVERIFIABLE, host.inspect(path, 1, 1L, 0).status());
        probe.failure = new WindowsFileAccessException(WindowsFileAccessException.Reason.UNCERTAIN, "uncertain probe");
        assertSame(probe.failure, assertThrows(IOException.class, () -> host.inspect(path, 1, 1L, 0)));
        assertEquals(2, ntfs.inspections);
    }

    @Test
    void disabledPolicyAndAdaptersNeverAcquireOrEstablishPhysicalAuthority() throws Exception {
        assertFalse(WindowsExfatSupport.PRODUCTION.available());
        assertThrows(WindowsFileAccessException.class, () -> WindowsExfatSupport.PRODUCTION.requireAvailable());
        assertThrows(WindowsFileAccessException.class, () -> new WindowsExfatNative().openDirectory(Path.of("C:/")));
        assertThrows(WindowsFileAccessException.class, () -> new WindowsExfatNative().openFile(Path.of("C:/A"), () -> { }));
        var exfat = new WindowsExfatHostFileSystem();
        assertThrows(WindowsFileAccessException.class, () -> exfat.openDirectoryChain(Path.of("C:/Photos")));
        assertThrows(WindowsFileAccessException.class, () -> exfat.openProtectedFile(Path.of("C:/A"), null, () -> { }));
        assertThrows(WindowsFileAccessException.class, () -> exfat.requireSessionStorage(Path.of("C:/")));
        assertTrue(HostFileSystems.select("Windows 11").supportsProfile(FileSystemProfile.NTFS));
        assertTrue(HostFileSystems.select("Mac OS X").supportsProfile(FileSystemProfile.APFS));
        assertFalse(HostFileSystems.select("Mac OS X").supportsProfile(FileSystemProfile.NTFS));
    }

    @Test
    void reconcilesTypesAndRejectsUncertainOrUnsupportedVolumeRoutes() throws Exception {
        var normal = volume("exFAT", 3, "C:\\", "\\Device\\TestVolume", List.of("C:\\"), false);
        assertTrue(WindowsVolumeProbe.classify("C:\\Photos", "exFAT", normal).ordinaryExfatRoute());
        assertEquals(FileSystemProfile.EXFAT, WindowsVolumeProbe.classify("C:\\Photos", "exFAT", normal).profile());
        assertThrows(WindowsFileAccessException.class, () -> WindowsVolumeProbe.classify("C:\\Photos", "NTFS", normal));
        assertThrows(WindowsFileAccessException.class, () -> WindowsVolumeProbe.classify("C:\\Photos", "", normal));
        for (var unsupported : List.of(
                volume("exFAT", 4, "C:\\", "\\Device\\Remote", List.of("C:\\"), false),
                volume("exFAT", 3, "C:\\", "\\??\\D:\\Other", List.of("C:\\"), false),
                volume("exFAT", 3, "C:\\mounted\\", "\\Device\\Test", List.of("C:\\mounted\\"), false),
                volume("exFAT", 3, "C:\\", "\\Device\\Test", List.of("C:\\", "D:\\"), false),
                volume("exFAT", 3, "C:\\", "\\Device\\Test", List.of("C:\\"), true))) {
            assertFalse(WindowsVolumeProbe.classify("C:\\Photos", "exFAT", unsupported).ordinaryExfatRoute());
        }
        assertEquals(FileSystemProfile.UNSUPPORTED,
                WindowsVolumeProbe.classify("C:\\Photos", "FAT32", volume("FAT32", 3, "C:\\", null, List.of(), false)).profile());
        var noNative = new WindowsVolumeProbe(path -> { fail("Unsupported syntax must not call native probe"); return null; });
        for (String path : List.of("\\\\server\\share\\A", "\\\\?\\C:\\A", "\\\\.\\C:", "C:\\A:stream", "C:\\A\\..\\B")) {
            assertFalse(WindowsVolumeProbe.ordinaryDrivePath(path));
        }
        assertEquals(FileSystemProfile.UNSUPPORTED, noNative.probe(Path.of("relative")).profile());
    }

    @Test
    void boundedNativeTextRequiresTerminationAndGuidShape() throws Exception {
        assertTrue(WindowsVolumeProbe.validGuid(GUID));
        assertFalse(WindowsVolumeProbe.validGuid("C:\\"));
        assertEquals(List.of("C:\\", "D:\\"), WindowsVolumeNative.multiString("C:\\\0D:\\\0\0".toCharArray(), 9));
        assertThrows(IOException.class, () -> WindowsVolumeNative.multiString("C:\\".toCharArray(), 3));
        assertThrows(IOException.class, () -> WindowsVolumeNative.terminated("unterminated".toCharArray()));
    }

    @Test
    void reconcilesNoFollowFactsAndDeniesLinksOtherTypesTimesAndVolumeContradictions() throws Exception {
        Path path = Path.of("C:\\A");
        var nio = org.mockito.Mockito.mock(BasicFileAttributes.class);
        org.mockito.Mockito.when(nio.isRegularFile()).thenReturn(true);
        org.mockito.Mockito.when(nio.size()).thenReturn(5L);
        org.mockito.Mockito.when(nio.lastModifiedTime()).thenReturn(java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
        var volume = WindowsVolumeProbe.classify("C:\\A", "exFAT",
                volume("exFAT", 3, "C:\\", "\\Device\\TestVolume", List.of("C:\\"), false));
        var observation = new WindowsExfatNativeAccess.Observation(0x20, "000012ab", "0000000000000001",
                5, 0, 0, 116444736000000000L, "\\\\?\\C:\\A");
        WindowsExfatHostFileSystem.reconcile(path, path, nio, false, observation, volume);
        org.mockito.Mockito.when(nio.isSymbolicLink()).thenReturn(true);
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(path, path, nio, false, observation, volume));
        org.mockito.Mockito.when(nio.isSymbolicLink()).thenReturn(false);
        org.mockito.Mockito.when(nio.isOther()).thenReturn(true);
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(path, path, nio, false, observation, volume));
        org.mockito.Mockito.when(nio.isOther()).thenReturn(false);
        org.mockito.Mockito.when(nio.lastModifiedTime()).thenReturn(java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(1)));
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(path, path, nio, false, observation, volume));
    }

    static WindowsVolumeProbe.NativeVolume volume(String type, int driveType, String root, String device,
            List<String> mounts, boolean redirected) {
        return new WindowsVolumeProbe.NativeVolume(type, "000012ab", GUID, driveType, root, device, mounts, redirected);
    }

    static final class FakeProbe extends WindowsVolumeProbe {
        FileSystemProfile profile = FileSystemProfile.NTFS;
        IOException failure;
        int calls;
        @Override public Result probe(Path path) throws IOException {
            calls++;
            if (failure != null) throw failure;
            return new Result(profile, profile.name(), null, profile == FileSystemProfile.EXFAT);
        }
    }

    static final class FakeNtfs implements HostFileSystem {
        int inspections;
        IOException failure;
        @Override public String pathText(LocationPath location) { return new WindowsNtfsHostFileSystem().pathText(location); }
        @Override public boolean unsafeElement(Path path, BasicFileAttributes attributes) { return false; }
        @Override public HostFileCheck inspect(LocationPath path, long size, Long second, Integer nano) throws IOException {
            inspections++;
            if (failure != null) throw failure;
            return HostFileCheck.established(Path.of("C:/Photos/A.jpg"), "native-id");
        }
    }
}
