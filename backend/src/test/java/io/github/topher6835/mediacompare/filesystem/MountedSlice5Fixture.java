package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.win32.*;

/** Opt-in mounted fixture: no access to user files outside this exact newly owned subtree. */
public final class MountedSlice5Fixture implements AutoCloseable {
    public final Path root = Path.of("Z:\\media-compare-slice5-acceptance");
    public final Map<Path, byte[]> files = new LinkedHashMap<>();
    public final CountedCalls calls = new CountedCalls();
    public final WindowsExfatNative nativeAccess = new WindowsExfatNative(calls, new WindowsExfatSupport(true));
    public final WindowsExfatHostFileSystem host = new WindowsExfatHostFileSystem(nativeAccess,
            new WindowsVolumeProbe(), new WindowsExfatSupport(true));
    private boolean owned;

    public void create() throws Exception {
        preflight();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Acceptance root already exists; refusing reuse");
        Files.createDirectory(root); owned = true;
        Files.createDirectory(root.resolve("nested"));
        files.put(root.resolve("fixture.png"), image("png"));
        files.put(root.resolve("fixture.jpeg"), orientedJpeg(image("jpeg")));
        files.put(root.resolve("nested/child.png"), image("png"));
        files.put(root.resolve("unsupported.txt"), "Slice 5 deterministic non-image\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var entry : files.entrySet()) {
            Files.write(entry.getKey(), entry.getValue(), StandardOpenOption.CREATE_NEW);
            System.out.println("FIXTURE " + entry.getKey() + " bytes=" + entry.getValue().length + " sha256=" + sha(entry.getValue()));
        }
    }

    public WindowsVolumeProbe.Result preflight() throws Exception {
        assertFalse(WindowsExfatSupport.PRODUCTION.available());
        assertTrue(Files.isDirectory(root.getRoot()));
        var result = new WindowsVolumeProbe().probe(root.getRoot());
        System.out.println("VOLUME " + result);
        assertEquals(FileSystemProfile.EXFAT, result.profile()); assertTrue(result.ordinaryExfatRoute());
        assertEquals("\\Device\\VeraCryptVolumeZ", result.nativeVolume().dosDevice());
        assertEquals("98d16f05", result.nativeVolume().serial());
        assertEquals(ExfatSlice3Fixtures.GUID, result.nativeVolume().guid());
        return result;
    }

    public void nativeQualification() throws Exception {
        for (var entry : files.entrySet()) {
            Path file = entry.getKey(); byte[] expected = entry.getValue();
            try (var chain = host.openDirectoryChain(file.getParent());
                    var held = host.openProtectedFile(file, chain, () -> {})) {
                assertEquals(file.getParent().getNameCount() + 1, chain.directoryCount());
                var channel = held.channel();
                ByteBuffer bytes = ByteBuffer.allocate(expected.length);
                while (bytes.hasRemaining()) assertTrue(channel.read(bytes) > 0);
                assertArrayEquals(expected, bytes.array()); assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
                channel.position(0); assertEquals(1, channel.read(ByteBuffer.allocate(1)));
                channel.position(expected.length / 2); var middle = ByteBuffer.allocate(1);
                assertEquals(1, channel.read(middle)); assertEquals(expected[expected.length / 2], middle.array()[0]);
                channel.position(expected.length); assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
                channel.position(5_000_000_000L); assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
                channel.position(0); bytes.clear(); while (bytes.hasRemaining()) assertTrue(channel.read(bytes) > 0);
                assertEquals(sha(expected), sha(bytes.array())); chain.revalidate(); held.observe();
                held.close(); assertFalse(channel.isOpen());
                assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            }
            assertEquals(0, calls.live());
            System.out.println("NATIVE PASS read/seek/EOF/hash/close " + file);
        }
        sharingQualification();
    }

    private void sharingQualification() throws Exception {
        Path file = root.resolve("fixture.png"), other = root.resolve("replacement.bin"), moved = root.resolve("moved.bin");
        Files.write(other, new byte[] {1,2,3}, StandardOpenOption.CREATE_NEW);
        var api = Native.load("kernel32", Mutations.class, W32APIOptions.UNICODE_OPTIONS);
        try (var chain = host.openDirectoryChain(root); var held = host.openProtectedFile(file, chain, () -> {})) {
            deniedOpen(api, file, 3); deniedOpen(api, file, 5);
            denied("delete", api.DeleteFile(file.toString()));
            denied("rename", api.MoveFileEx(file.toString(), moved.toString(), 0));
            denied("move", api.MoveFileEx(file.toString(), root.resolve("nested/moved.bin").toString(), 0));
            denied("replace", api.MoveFileEx(other.toString(), file.toString(), 1));
            HANDLE rootDelete = api.CreateFile(root.toString(), 0x10000, 7, null, 3, 0x02200000, null);
            boolean rejected = invalid(rootDelete); int rootError = rejected ? Native.getLastError() : 0;
            if (!rejected) assertTrue(api.CloseHandle(rootDelete));
            System.out.println("DENIED retained-root-delete-access error=" + rootError); assertTrue(rejected); assertEquals(32, rootError);
        }
        assertEquals(0, calls.live());
        HANDLE rootDeleteControl = api.CreateFile(root.toString(), 0x10000, 7, null, 3, 0x02200000, null);
        assertFalse(invalid(rootDeleteControl)); assertTrue(api.CloseHandle(rootDeleteControl));
        Path parent = root.resolve("nested"), renamedParent = root.resolve("parent-renamed");
        try (var chain = host.openDirectoryChain(parent); var held = host.openProtectedFile(parent.resolve("child.png"), chain, () -> {})) {
            denied("retained-parent-rename", api.MoveFileEx(parent.toString(), renamedParent.toString(), 0));
        }
        assertTrue(api.MoveFileEx(parent.toString(), renamedParent.toString(), 0));
        assertTrue(api.MoveFileEx(renamedParent.toString(), parent.toString(), 0));
        // Every control is restored before the content-read fixture is used.
        for (int disposition : new int[] {3,5}) {
            HANDLE writer = api.CreateFile(file.toString(), 0x40000000, 7, null, disposition, 0x00200000, null);
            assertFalse(invalid(writer)); assertTrue(api.CloseHandle(writer));
            Files.write(file, files.get(file));
        }
        assertTrue(api.MoveFileEx(file.toString(), moved.toString(), 0)); assertTrue(api.MoveFileEx(moved.toString(), file.toString(), 0));
        Path nestedMove = root.resolve("nested/moved.bin");
        assertTrue(api.MoveFileEx(file.toString(), nestedMove.toString(), 0)); assertTrue(api.MoveFileEx(nestedMove.toString(), file.toString(), 0));
        assertTrue(api.MoveFileEx(other.toString(), file.toString(), 1)); Files.write(file, files.get(file));
        assertTrue(api.DeleteFile(file.toString())); Files.write(file, files.get(file), StandardOpenOption.CREATE_NEW);
        Path empty = Files.createDirectory(root.resolve("empty"));
        try (var chain = host.openDirectoryChain(empty)) {
            denied("retained-empty-directory-delete", api.RemoveDirectory(empty.toString()));
            denied("retained-directory-rename", api.MoveFileEx(empty.toString(), root.resolve("empty-renamed").toString(), 0));
            denied("retained-directory-move", api.MoveFileEx(empty.toString(), root.resolve("nested/empty").toString(), 0));
        }
        Path renamed = root.resolve("empty-renamed");
        assertTrue(api.MoveFileEx(empty.toString(), renamed.toString(), 0)); assertTrue(api.MoveFileEx(renamed.toString(), empty.toString(), 0));
        Path emptyMoved = root.resolve("nested/empty");
        assertTrue(api.MoveFileEx(empty.toString(), emptyMoved.toString(), 0)); assertTrue(api.MoveFileEx(emptyMoved.toString(), empty.toString(), 0));
        assertTrue(api.RemoveDirectory(empty.toString()));
        HANDLE writer = api.CreateFile(file.toString(), 0xc0000000, 7, null, 3, 0x00200000, null);
        assertFalse(invalid(writer)); HANDLE mapping = null; Pointer view = null;
        try {
            blockedAcquisition(file, "existing-writer");
            mapping = api.CreateFileMapping(writer, null, 4, 0, 0, null);
            int mappingError = invalid(mapping) ? Native.getLastError() : 0;
            System.out.println("WRITABLE MAPPING creation error=" + mappingError); assertFalse(invalid(mapping));
            view = api.MapViewOfFile(mapping, 2, 0, 0, new SIZE_T(0)); assertNotNull(view);
            blockedAcquisition(file, "writable-map-and-writer");
            assertTrue(api.CloseHandle(writer)); writer = null;
            blockedAcquisition(file, "writable-map-after-writer-close");
        } finally {
            if (view != null) assertTrue(api.UnmapViewOfFile(view));
            if (mapping != null) assertTrue(api.CloseHandle(mapping));
            if (writer != null) assertTrue(api.CloseHandle(writer));
        }
        try (var held = nativeAccess.openFile(file, () -> {})) { assertEquals(files.get(file).length, held.observe().size()); }
        assertEquals(0, calls.live());
        System.out.println("SHARING PASS all post-close controls and mapping cleanup");
    }
    private void blockedAcquisition(Path file, String label) {
        var failure = assertThrows(WindowsFileAccessException.class, () -> nativeAccess.openFile(file, () -> {}));
        System.out.println("DENIED " + label + " error=" + failure.nativeError()); assertEquals(32, failure.nativeError());
    }
    private static boolean invalid(HANDLE handle) { return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == -1; }
    private static void denied(String label, boolean success) {
        int error = success ? 0 : Native.getLastError(); System.out.println("DENIED " + label + " success=" + success + " error=" + error);
        assertFalse(success); assertTrue(error == 32 || error == 5);
    }
    private static void deniedOpen(Mutations api, Path path, int disposition) {
        HANDLE writer = api.CreateFile(path.toString(), 0x40000000, 7, null, disposition, 0x00200000, null);
        boolean denied = invalid(writer); int error = denied ? Native.getLastError() : 0;
        if (!denied) assertTrue(api.CloseHandle(writer));
        System.out.println("DENIED writer disposition=" + disposition + " error=" + error); assertTrue(denied); assertEquals(32, error);
    }
    public interface Mutations extends StdCallLibrary {
        HANDLE CreateFile(String path, int access, int share, Pointer security, int disposition, int flags, HANDLE template);
        boolean CloseHandle(HANDLE handle); boolean DeleteFile(String path); boolean RemoveDirectory(String path);
        boolean MoveFileEx(String from, String to, int flags);
        HANDLE CreateFileMapping(HANDLE file, Pointer security, int protection, int high, int low, String name);
        Pointer MapViewOfFile(HANDLE mapping, int access, int high, int low, SIZE_T size); boolean UnmapViewOfFile(Pointer view);
    }
    public static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] image(String format) throws IOException {
        var image = new BufferedImage(43, 27, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 27; y++) for (int x = 0; x < 43; x++) image.setRGB(x, y, (x * 5 << 16) | (y * 9 << 8) | ((x + y) * 3));
        var output = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(output)) { assertTrue(ImageIO.write(image, format, stream)); }
        return output.toByteArray();
    }
    private static byte[] orientedJpeg(byte[] jpeg) throws IOException {
        // APP1 Exif, little-endian TIFF, one SHORT Orientation=6 entry.
        byte[] exif = HexFormat.of().parseHex("ffe1002245786966000049492a0008000000010012010300010000000600000000000000");
        var out = new ByteArrayOutputStream(); out.write(jpeg, 0, 2); out.write(exif); out.write(jpeg, 2, jpeg.length - 2); return out.toByteArray();
    }
    @Override public void close() throws Exception {
        if (!owned) return;
        assertEquals(0, calls.live(), "Refusing cleanup with retained native resources"); preflight();
        // Validate the exact root and every occupant, then use individually named deletes only.
        assertEquals(Path.of("Z:\\media-compare-slice5-acceptance"), root.toRealPath(LinkOption.NOFOLLOW_LINKS));
        var known = new HashSet<>(files.keySet());
        for (String name : List.of("nested", "replacement.bin", "moved.bin", "nested/moved.bin", "empty", "empty-renamed", "nested/empty")) known.add(root.resolve(name));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                assertTrue(path.equals(root) || known.contains(path), "Unknown fixture occupant; cleanup refused: " + path);
                assertFalse(Files.isSymbolicLink(path));
                var attributes = Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                assertFalse(attributes.isOther());
            }
        }
        for (Path path : known.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) Files.deleteIfExists(path);
        Files.delete(root); assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS)); owned = false;
        System.out.println("CLEANUP PASS acceptance root absent; native handles=0");
    }

    /** Calls delegate unchanged to the production JNA implementation; successful closes are counted. */
    public static final class CountedCalls implements WindowsExfatNative.Calls {
        private final WindowsExfatNativeCalls delegate = new WindowsExfatNativeCalls();
        private final Map<WindowsExfatNative.NativeHandle, Path> live = new HashMap<>();
        public int fileOpens;
        public synchronized int live() { return live.size(); }
        @Override public synchronized WindowsExfatNative.NativeHandle open(Path path, int access, int share, int disposition, int flags) throws IOException {
            var handle = delegate.open(path, access, share, disposition, flags); live.put(handle, path);
            if ((flags & WindowsExfatNative.BACKUP) == 0) fileOpens++;
            System.out.println("NATIVE OPEN " + path + " share=" + share + " flags=" + flags); return handle;
        }
        @Override public int handleFlags(WindowsExfatNative.NativeHandle handle) throws IOException { return delegate.handleFlags(handle); }
        @Override public WindowsExfatNativeAccess.Observation observe(WindowsExfatNative.NativeHandle handle) throws IOException { return delegate.observe(handle); }
        @Override public long seek(WindowsExfatNative.NativeHandle handle, long position) throws IOException { return delegate.seek(handle, position); }
        @Override public int read(WindowsExfatNative.NativeHandle handle, byte[] bytes, int count) throws IOException { return delegate.read(handle, bytes, count); }
        @Override public synchronized void close(WindowsExfatNative.NativeHandle handle) throws IOException {
            delegate.close(handle); System.out.println("NATIVE CLOSE " + live.remove(handle) + " remaining=" + live.size());
        }
    }
}
