package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Disposable NTFS fixtures exercise mechanics; they do not qualify exFAT authority. */
@EnabledOnOs(OS.WINDOWS)
class WindowsProtectedAccessTests {
    @TempDir(factory = CheckoutTempDirFactory.class) Path directory;

    @Test
    void realNativeChannelReadsSeeksObservesAndClosesOnNtfs() throws Exception {
        assertEquals("NTFS", Files.getFileStore(directory).type().toUpperCase());
        Path file = Files.write(directory.resolve("Protected data.bin"), new byte[] {1, 2, 3, 4}).toRealPath();
        var probe = new WindowsVolumeProbe().probe(file);
        assertEquals(FileSystemProfile.NTFS, probe.profile());
        var access = new WindowsExfatNative(new WindowsExfatNativeCalls(), new WindowsExfatSupport(true));
        try (var folder = access.openDirectory(directory);
                var held = access.openFile(file, () -> { })) {
            assertTrue(folder.observe().directory());
            assertEquals(4, held.observe().size());
            assertEquals(probe.nativeVolume().serial(), held.observe().volumeSerial());
            ByteBuffer bytes = ByteBuffer.allocate(4);
            assertEquals(4, held.channel().read(bytes));
            assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes.array());
            assertEquals(-1, held.channel().read(ByteBuffer.allocate(1)));
            held.channel().position(2);
            bytes.clear().limit(1);
            assertEquals(1, held.channel().read(bytes));
            assertEquals(3, bytes.array()[0]);
            held.channel().position(5_000_000_000L);
            assertEquals(-1, held.channel().read(ByteBuffer.allocate(1)));
        }
        Files.delete(file);
        assertFalse(Files.exists(file));
    }

    @Test
    void partialChainFailureClosesAllOwnedLeasesInReverseOrder() throws Exception {
        Path root = Files.createDirectories(directory.resolve("Source/Child")).toRealPath();
        var access = new FakeAccess();
        access.failAt = root.getNameCount();
        var host = new WindowsExfatHostFileSystem(access, fakeVolumes(), new WindowsExfatSupport(true));
        assertThrows(IOException.class, () -> host.openDirectoryChain(root));
        var expectedClosed = new ArrayList<>(access.opened);
        java.util.Collections.reverse(expectedClosed);
        assertEquals(expectedClosed, access.closed);
    }

    @Test
    void reconcilesFilesAndDirectoriesWithoutFreezingDirectoryMtime() throws Exception {
        Path file = Files.writeString(directory.resolve("A.bin"), "bytes").toRealPath();
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        var volume = fakeVolumes().probe(file);
        var normal = observation(file, attrs);
        WindowsExfatHostFileSystem.reconcile(file, file, attrs, false, normal, volume);
        var wrongSerial = new WindowsExfatNativeAccess.Observation(normal.attributes(), "00000000", normal.legacyIndex(),
                normal.size(), 0, 0, normal.modified100ns(), normal.finalPath());
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(file, file, attrs, false, wrongSerial, volume));
        var reparse = new WindowsExfatNativeAccess.Observation(0x400, normal.volumeSerial(), normal.legacyIndex(),
                normal.size(), 0, 0, normal.modified100ns(), normal.finalPath());
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(file, file, attrs, false, reparse, volume));
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(file, file, attrs, true, normal, volume));
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(file, file.resolveSibling("Other"), attrs, false, normal, volume));
        var changedBytes = new WindowsExfatNativeAccess.Observation(0, normal.volumeSerial(), normal.legacyIndex(),
                99, 0, 0, normal.modified100ns(), normal.finalPath());
        assertThrows(IOException.class, () -> WindowsExfatHostFileSystem.reconcile(file, file, attrs, false, changedBytes, volume));
        BasicFileAttributes folder = Files.readAttributes(directory, BasicFileAttributes.class);
        var folderObservation = observation(directory, folder);
        var oldMtime = new WindowsExfatNativeAccess.Observation(0x10, folderObservation.volumeSerial(), "0", 0, 0, 0, 0, folderObservation.finalPath());
        WindowsExfatHostFileSystem.reconcile(directory, directory, folder, true, oldMtime, volume);
    }

    @Test
    void channelCloseReleasesChainUserAndChainCloseDrainsWithoutClosingActiveFile() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("Source")).toRealPath();
        Path file = Files.writeString(folder.resolve("A.bin"), "bytes");
        var calls = new WindowsExfatNativeCalls();
        var nativeAccess = new WindowsExfatNative(calls, new WindowsExfatSupport(true));
        // Use actual NTFS serial in the injected exFAT route, qualifying only ownership mechanics.
        var nativeVolume = new WindowsVolumeProbe().probe(file).nativeVolume();
        var volumes = new WindowsVolumeProbe() {
            @Override public Result probe(Path path) {
                var route = new NativeVolume("exFAT", nativeVolume.serial(),
                        "\\\\?\\Volume{11111111-2222-3333-4444-555555555555}\\", 3,
                        path.getRoot().toString(), "\\Device\\Fixture", List.of(path.getRoot().toString()), false);
                return new Result(FileSystemProfile.EXFAT, "exFAT", route, true);
            }
        };
        var host = new WindowsExfatHostFileSystem(nativeAccess, volumes, new WindowsExfatSupport(true));
        var chain = host.openDirectoryChain(folder);
        var held = host.openProtectedFile(file, chain, () -> { });
        assertEquals(5, held.observe().size());
        assertThrows(IOException.class, chain::close);
        assertEquals(1, held.channel().read(ByteBuffer.allocate(1)));
        held.channel().close();
        chain.close();
        held.close();
        assertFalse(held.channel().isOpen());
        assertThrows(IOException.class, chain::revalidate);
        Files.delete(file);
    }

    private static WindowsVolumeProbe fakeVolumes() {
        return new WindowsVolumeProbe() {
            @Override public Result probe(Path path) {
                var volume = WindowsHostFileSystemTests.volume("exFAT", 3, path.getRoot().toString(),
                        "\\Device\\Fixture", List.of(path.getRoot().toString()), false);
                return new Result(FileSystemProfile.EXFAT, "exFAT", volume, true);
            }
        };
    }

    private static WindowsExfatNativeAccess.Observation observation(Path path, BasicFileAttributes attrs) {
        long ticks = Math.addExact(116444736000000000L,
                Math.addExact(Math.multiplyExact(attrs.lastModifiedTime().toInstant().getEpochSecond(), 10000000),
                        attrs.lastModifiedTime().toInstant().getNano() / 100));
        return new WindowsExfatNativeAccess.Observation(attrs.isDirectory() ? 0x10 : 0x20, "000012ab", "0",
                attrs.size(), 0, 0, ticks, "\\\\?\\" + path);
    }

    private static final class FakeAccess implements WindowsExfatNativeAccess {
        final List<Path> opened = new ArrayList<>(), closed = new ArrayList<>();
        int failAt = -1;
        @Override public Lease openDirectory(Path path) throws IOException {
            if (opened.size() == failAt) throw new IOException("chain acquisition failed");
            opened.add(path);
            return new Lease() {
                @Override public Observation observe() throws IOException {
                    return observation(path, Files.readAttributes(path, BasicFileAttributes.class));
                }
                @Override public void close() { closed.add(path); }
            };
        }
        @Override public FileLease openFile(Path path, Checkpoint checkpoint) { throw new UnsupportedOperationException(); }
    }
}
