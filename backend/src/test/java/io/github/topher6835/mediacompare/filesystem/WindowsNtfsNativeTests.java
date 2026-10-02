package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.HexFormat;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPathParser;

@EnabledOnOs(OS.WINDOWS)
class WindowsNtfsNativeTests {
    @TempDir(factory = CheckoutTempDirFactory.class) Path directory;

    @Test
    void stableFileDirectoryAndDriveIdentitiesAndHandleClosure() throws Exception {
        Assumptions.assumeTrue("NTFS".equalsIgnoreCase(Files.getFileStore(directory).type()));
        Path file = Files.writeString(directory.resolve("MixedCase.jpg"), "bytes");
        var original = WindowsNtfsNative.observe(file);
        var folder = WindowsNtfsNative.observe(directory);
        var drive = WindowsNtfsNative.observe(directory.getRoot());
        assertFalse(original.directory());
        assertTrue(folder.directory());
        assertTrue(drive.directory());
        assertFalse(original.reparsePoint());
        assertEquals(drive.identity().volumeSerial(), original.identity().volumeSerial());
        assertEquals(drive.identity().volumeSerial(), folder.identity().volumeSerial());
        assertEquals(16, original.identity().volumeSerial().length());
        assertEquals(32, original.identity().fileId().length());
        assertNotEquals(folder.identity().fileId(), original.identity().fileId());
        assertEquals(original, WindowsNtfsNative.observe(file.resolveSibling("MIXEDCASE.JPG")));
        assertEquals(folder, WindowsNtfsNative.observe(Path.of(directory.toString().toUpperCase(Locale.ROOT))));
        // Other Spring tests leave background activity in the shared JVM. Isolate the process-wide count.
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), WindowsNtfsHandleProbe.class.getName(), file.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "Handle probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            System.out.println("NTFS native acceptance: file=" + original.identity()
                    + ", directory=" + folder.identity() + ", drive=" + drive.identity() + "; " + output.strip());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test
    void volumeSerialAndRawFileIdSerializationAgreeWithWindowsTools() throws Exception {
        Path file = Files.writeString(directory.resolve("Native ID with spaces.jpg"), "bytes");
        var identity = WindowsNtfsNative.observe(file).identity();
        var serial = new IntByReference();
        assertTrue(Kernel32.INSTANCE.GetVolumeInformation(directory.getRoot().toString(), null, 0,
                serial, new IntByReference(), new IntByReference(), null, 0));
        assertEquals(String.format("%08x", serial.getValue()), identity.volumeSerial().substring(8));
        var process = new ProcessBuilder("fsutil.exe", "file", "queryfileid", file.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "File ID query timed out");
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.defaultCharset());
            assertEquals(0, process.exitValue(), output);
            // FILE_ID_128 is persisted as opaque bytes in returned order; fsutil prints the integer in reverse.
            byte[] raw = HexFormat.of().parseHex(identity.fileId());
            for (int index = 0; index < raw.length / 2; index++) {
                byte swap = raw[index];
                raw[index] = raw[raw.length - index - 1];
                raw[raw.length - index - 1] = swap;
            }
            assertTrue(output.toLowerCase(Locale.ROOT).contains("0x" + HexFormat.of().formatHex(raw)), output);
            System.out.println("NTFS serialization acceptance: " + identity + "; " + output.strip());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void sameSizeAndTimestampReplacementChangesNativeIdentity() throws Exception {
        Path file = Files.writeString(directory.resolve("original.jpg"), "first");
        var time = Files.getLastModifiedTime(file);
        var location = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, file.toString());
        var before = HostFileSystems.current().inspect(location, 5,
                time.toInstant().getEpochSecond(), time.toInstant().getNano());
        assertEquals(HostFileStatus.ESTABLISHED, before.status());
        var identity = WindowsNtfsNative.observe(file).identity();
        Path replacement = Files.writeString(directory.resolve("replacement.jpg"), "other");
        Files.setLastModifiedTime(replacement, time);
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        var after = HostFileSystems.current().inspect(location, 5,
                time.toInstant().getEpochSecond(), time.toInstant().getNano());
        assertEquals(HostFileStatus.ESTABLISHED, after.status());
        assertFalse(before.sameFileAs(after));
        assertNotEquals(identity, WindowsNtfsNative.observe(file).identity());
        Path folder = Files.createDirectory(directory.resolve("folder"));
        var folderIdentity = WindowsNtfsNative.observe(folder).identity();
        Files.move(folder, directory.resolve("old-folder"));
        Files.createDirectory(folder);
        assertNotEquals(folderIdentity, WindowsNtfsNative.observe(folder).identity());
    }

    static int handleCount() {
        var count = new IntByReference();
        assertTrue(HandleCounter.INSTANCE.GetProcessHandleCount(Kernel32.INSTANCE.GetCurrentProcess(), count));
        return count.getValue();
    }

    public interface HandleCounter extends StdCallLibrary {
        HandleCounter INSTANCE = Native.load("kernel32", HandleCounter.class);
        boolean GetProcessHandleCount(HANDLE process, IntByReference count);
    }
}
