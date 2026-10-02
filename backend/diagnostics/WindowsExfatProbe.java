import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.*;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsNative;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;
import io.github.topher6835.mediacompare.session.SourceWorkspaceOverlapException;

/** Manual diagnostic only. Never participates in Source authority or the normal test suite. */
public class WindowsExfatProbe {
    private static final Path DRIVE = Path.of("Z:\\");
    private static final Path ROOT = DRIVE.resolve("media-compare-authority-test");
    private static final String MARKER = "Media Compare disposable exFAT diagnostic v1";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final ExtraKernel NATIVE = Native.load("kernel32", ExtraKernel.class,
            W32APIOptions.UNICODE_OPTIONS);

    public interface ExtraKernel extends StdCallLibrary {
        int QueryDosDevice(String device, char[] target, int size);
        boolean GetVolumeNameForVolumeMountPoint(String root, char[] name, int size);
        boolean GetFileInformationByHandle(HANDLE file, Pointer information);
        int GetFinalPathNameByHandle(HANDLE file, char[] name, int size, int flags);
        boolean SetFileTime(HANDLE file, Pointer creation, Pointer access, Pointer modified);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("run", "followup", "resume", "cleanup").contains(args[0])) {
            throw new IllegalArgumentException("Usage: WindowsExfatProbe run|followup|resume|cleanup expectedVolumeSerialHex");
        }
        verifyVolume(args[1]);
        if (args[0].equals("run")) run(args[1]);
        else {
            requireMarker();
            if (args[0].equals("resume")) resume();
            else if (args[0].equals("followup")) followup();
            else cleanupRetained();
        }
    }

    private static void verifyVolume(String expectedSerial) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new IOException("Windows only");
        char[] device = new char[1024];
        int result = NATIVE.QueryDosDevice("Z:", device, device.length);
        if (result == 0) throw new IOException("QueryDosDevice error " + Native.getLastError());
        String deviceName = Native.toString(device);
        FileStore store = Files.getFileStore(DRIVE);
        char[] label = new char[256], filesystem = new char[256], volumeName = new char[1024];
        var serial = new IntByReference();
        var maximum = new IntByReference();
        var flags = new IntByReference();
        if (!Kernel32.INSTANCE.GetVolumeInformation(DRIVE.toString(), label, label.length, serial,
                maximum, flags, filesystem, filesystem.length)) {
            throw new IOException("GetVolumeInformation error " + Native.getLastError());
        }
        String serialHex = String.format("%08x", serial.getValue());
        if (!deviceName.equals("\\Device\\VeraCryptVolumeZ") || !store.type().equalsIgnoreCase("exFAT")
                || !Native.toString(filesystem).equalsIgnoreCase("exFAT") || !serialHex.equals(expectedSerial)) {
            throw new IOException("Selected Z: volume is not the verified VeraCrypt/exFAT test volume: device="
                    + deviceName + "; NIO filesystem=" + store.type() + "; native filesystem="
                    + Native.toString(filesystem) + "; actual serial=" + serialHex + "; expected serial=" + expectedSerial);
        }
        out("environment", "os=" + System.getProperty("os.name") + "; version=" + System.getProperty("os.version")
                + "; java=" + System.getProperty("java.version") + "; timezone=" + TimeZone.getDefault().getID());
        out("volume", "device=" + deviceName + "; nativeFs=" + Native.toString(filesystem)
                + "; label=" + Native.toString(label) + "; serial=" + serialHex
                + "; maximumComponent=" + maximum.getValue() + "; flags=" + String.format("%08x", flags.getValue()));
        out("nio-store", "name=" + store.name() + "; type=" + store.type() + "; text=" + store
                + "; total=" + store.getTotalSpace() + "; readOnly=" + store.isReadOnly()
                + "; basic=" + store.supportsFileAttributeView("basic") + "; dos=" + store.supportsFileAttributeView("dos")
                + "; acl=" + store.supportsFileAttributeView("acl") + "; posix=" + store.supportsFileAttributeView("posix"));
        boolean named = NATIVE.GetVolumeNameForVolumeMountPoint(DRIVE.toString(), volumeName, volumeName.length);
        out("volume-guid", named ? Native.toString(volumeName) : "error=" + Native.getLastError());
        capture("volume-root", DRIVE);
    }

    private static void run(String serial) throws Exception {
        if (Files.exists(ROOT, NOFOLLOW)) throw new IOException("Fixture already exists; refusing to reuse it: " + ROOT);
        Files.createDirectory(ROOT);
        Files.writeString(ROOT.resolve("owner.txt"), MARKER, StandardOpenOption.CREATE_NEW);
        Path experiment = Files.createDirectory(ROOT.resolve("Experiment"));
        try {
            fileLifecycle(experiment);
            directoryLifecycle(experiment);
            casing(experiment);
            timestamps(experiment);
            reparseAndSeparation(experiment);
        } finally {
            removeExperiment(experiment);
        }
        Path retained = Files.createDirectory(ROOT.resolve("RemountEvidence"));
        Path file = Files.writeString(retained.resolve("Stable.bin"), "remount evidence", StandardOpenOption.CREATE_NEW);
        var baseline = new Properties();
        baseline.setProperty("serial", serial);
        baseline.setProperty("root", legacyIdentity(DRIVE));
        baseline.setProperty("directory", legacyIdentity(retained));
        baseline.setProperty("file", legacyIdentity(file));
        baseline.setProperty("mtime", Files.getLastModifiedTime(file).toString());
        try (var output = Files.newOutputStream(ROOT.resolve("baseline.properties"), StandardOpenOption.CREATE_NEW)) {
            baseline.store(output, MARKER);
        }
        capture("retained-directory", retained);
        capture("retained-file", file);
        resume(); // Same-mount reopen control; this does not claim remount acceptance.
        out("retained", ROOT + "; all experimental files removed; only marked remount evidence retained");
    }

    private static void fileLifecycle(Path tree) throws Exception {
        Path file = Files.writeString(tree.resolve("Original.bin"), "first");
        FileTime originalTime = Files.getLastModifiedTime(file);
        capture("file-created", file);
        capture("file-reopened", file);
        file = Files.move(file, file.resolveSibling("Renamed.bin"));
        capture("file-renamed", file);
        Path destination = Files.createDirectory(tree.resolve("Destination"));
        file = Files.move(file, destination.resolve("Moved.bin"));
        capture("file-moved", file);
        var original = Files.readAttributes(file, BasicFileAttributes.class);
        Files.delete(file);
        Files.writeString(file, "other", StandardOpenOption.CREATE_NEW);
        Files.setLastModifiedTime(file, original.lastModifiedTime());
        capture("file-delete-recreate-same-size-mtime", file);
        Files.getFileAttributeView(file, BasicFileAttributeView.class).setTimes(
                original.lastModifiedTime(), original.lastAccessTime(), original.creationTime());
        capture("file-replacement-all-times-restored", file);
        for (int i = 0; i < 6; i++) {
            Files.delete(file);
            Files.writeString(file, i % 2 == 0 ? "first" : "other", StandardOpenOption.CREATE_NEW);
            Files.setLastModifiedTime(file, originalTime);
            capture("file-recreate-loop-" + i, file);
        }
        Path replacement = Files.writeString(destination.resolve("Replacement.bin"), "third");
        Files.setLastModifiedTime(replacement, originalTime);
        capture("separate-replacement-before-move", replacement);
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        capture("separate-replacement-after-move", file);
    }

    private static void directoryLifecycle(Path tree) throws Exception {
        Path folder = Files.createDirectory(tree.resolve("OriginalDirectory"));
        capture("directory-created", folder);
        capture("directory-reopened", folder);
        folder = Files.move(folder, folder.resolveSibling("RenamedDirectory"));
        capture("directory-renamed", folder);
        folder = Files.move(folder, tree.resolve("Destination").resolve("MovedDirectory"));
        capture("directory-moved", folder);
        var before = Files.readAttributes(folder, BasicFileAttributes.class);
        Files.delete(folder);
        Files.createDirectory(folder);
        Files.getFileAttributeView(folder, BasicFileAttributeView.class).setTimes(
                before.lastModifiedTime(), before.lastAccessTime(), before.creationTime());
        capture("directory-delete-recreate-all-times-restored", folder);
    }

    private static void casing(Path tree) throws Exception {
        Path directory = Files.createDirectory(tree.resolve("MixedCaseDirectory"));
        Path file = Files.writeString(directory.resolve("Example.jpg"), "case");
        Path alternate = Path.of(file.toString().toUpperCase(Locale.ROOT));
        Path alternateDirectory = Path.of(directory.toString().toLowerCase(Locale.ROOT));
        out("case-file", "equals=" + file.equals(alternate) + "; sameFile=" + Files.isSameFile(file, alternate)
                + "; real=" + alternate.toRealPath() + "; realNoFollow=" + alternate.toRealPath(NOFOLLOW)
                + "; canonical=" + alternate.toFile().getCanonicalPath());
        out("case-directory", "equals=" + directory.equals(alternateDirectory)
                + "; sameFile=" + Files.isSameFile(directory, alternateDirectory)
                + "; real=" + alternateDirectory.toRealPath());
        try (var entries = Files.list(directory)) { out("case-enumeration", entries.toList()); }
        capture("case-file-original", file);
        capture("case-file-alternate", alternate);
        capture("case-directory-original", directory);
        capture("case-directory-alternate", alternateDirectory);
    }

    private static void timestamps(Path tree) throws Exception {
        Path file = Files.writeString(tree.resolve("Timestamp.bin"), "time");
        long base = Instant.parse("2026-01-15T12:34:56Z").getEpochSecond();
        long[] fractions = {0, 1, 9_999_999, 10_000_000, 19_999_999, 123_456_789, 999_999_999,
                1_000_000_000L, 1_001_000_000L, 1_999_999_999L};
        for (long fraction : fractions) {
            FileTime requested = FileTime.from(Instant.ofEpochSecond(base).plusNanos(fraction));
            Files.getFileAttributeView(file, BasicFileAttributeView.class).setTimes(requested, requested, requested);
            capture("timestamp-request-" + requested, file);
        }
        FileTime requested = FileTime.from(Instant.parse("2026-01-15T12:34:57.123456789Z"));
        Files.getFileAttributeView(file, BasicFileAttributeView.class).setTimes(requested, requested, requested);
        capture("timestamp-before-read", file);
        Files.readAllBytes(file);
        capture("timestamp-after-read", file);
        Thread.sleep(1100);
        capture("timestamp-after-read-delayed", file);
        Path copy = Files.copy(file, tree.resolve("Copied.bin"), StandardCopyOption.COPY_ATTRIBUTES);
        capture("timestamp-copy-attributes", copy);
        Files.writeString(file, "edit", StandardOpenOption.TRUNCATE_EXISTING);
        capture("timestamp-after-write", file);
        Path plainCopy = Files.copy(file, tree.resolve("PlainCopy.bin"));
        capture("timestamp-copy-default", plainCopy);
    }

    private static void reparseAndSeparation(Path tree) throws Exception {
        Path target = Files.createDirectory(tree.resolve("AliasTarget"));
        Path file = Files.writeString(target.resolve("Target.bin"), "target");
        alias("exfat-file-symlink", tree.resolve("FileLink"), file, false);
        alias("exfat-directory-symlink", tree.resolve("DirectoryLink"), target, false);
        alias("exfat-junction", tree.resolve("Junction"), target, true);
        Path local = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "exfat-alias-");
        try {
            out("ntfs-alias-store", Files.getFileStore(local).type());
            alias("ntfs-file-symlink-into-exfat", local.resolve("FileLink"), file, false);
            alias("ntfs-directory-symlink-into-exfat", local.resolve("DirectoryLink"), target, false);
            Path ntfsAlias = local.resolve("IntoExfat");
            alias("ntfs-junction-into-exfat", ntfsAlias, target, true);
            Path ntfsSession = Files.createDirectory(local.resolve("Session"));
            overlap("ntfs-session-exfat-source", ntfsSession.toRealPath(), tree);
            overlap("exfat-session-ntfs-source", target.toRealPath(), ntfsSession);
            overlap("exfat-equal", target.toRealPath(), target);
            overlap("exfat-alternate-case", target.toRealPath(), Path.of(target.toString().toUpperCase(Locale.ROOT)));
            overlap("exfat-parent", target.toRealPath(), tree);
            overlap("exfat-missing-child", target.toRealPath(), target.resolve("missing"));
            overlap("exfat-drive-root", target.toRealPath(), DRIVE);
            overlap("exfat-separate-sibling", target.toRealPath(), tree.resolve("Destination"));
            Files.delete(ntfsSession);
        } finally { Files.delete(local); }
    }

    private static void alias(String label, Path alias, Path target, boolean junction) throws Exception {
        try {
            if (junction) {
                var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                        alias.toString(), target.toString()).redirectErrorStream(true).start();
                try {
                    if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("mklink timed out");
                    out(label + "-command", "exit=" + process.exitValue() + "; "
                            + new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset()).strip());
                } finally { if (process.isAlive()) process.destroyForcibly(); }
            } else Files.createSymbolicLink(alias, target);
            if (Files.exists(alias, NOFOLLOW)) {
                capture(label + "-alias", alias);
                out(label + "-resolved", "real=" + alias.toRealPath() + "; noFollow=" + alias.toRealPath(NOFOLLOW)
                        + "; sameFile=" + Files.isSameFile(alias, target));
                if (Files.isDirectory(target)) {
                    capture(label + "-descendant", alias.resolve("Target.bin"));
                    overlap(label + "-overlap", target.toRealPath(), alias);
                    overlap(label + "-missing-overlap", target.toRealPath(), alias.resolve("missing"));
                }
            }
        } catch (IOException | UnsupportedOperationException exception) {
            out(label + "-unavailable", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            // Unlink only the alias itself. Never recursively delete a reparse target.
            if (Files.exists(alias, NOFOLLOW)) Files.delete(alias);
        }
    }

    private static void overlap(String label, Path session, Path source) {
        try {
            new SessionSourceBoundary(session).requireSeparate(source.toString());
            out(label, "ALLOWED");
        } catch (SourceWorkspaceOverlapException exception) { out(label, "REJECTED"); }
    }

    private static void capture(String label, Path path) throws IOException {
        var a = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        out(label + "-nio", "path=" + path + "; real=" + path.toRealPath() + "; key=" + a.fileKey()
                + "; directory=" + a.isDirectory() + "; regular=" + a.isRegularFile() + "; symlink=" + a.isSymbolicLink()
                + "; other=" + a.isOther() + "; size=" + a.size() + "; mtime=" + a.lastModifiedTime()
                + "; creation=" + a.creationTime() + "; access=" + a.lastAccessTime());
        out(label + "-native", nativeInformation(path));
        try { out(label + "-existing-ntfs-adapter", WindowsNtfsNative.observe(path)); }
        catch (IOException | IllegalArgumentException exception) { out(label + "-existing-ntfs-adapter", exception.getMessage()); }
    }

    private static String nativeInformation(Path path) throws IOException {
        HANDLE handle = Kernel32.INSTANCE.CreateFile(path.toString(), 0, 7, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException("CreateFile error " + Native.getLastError());
        try {
            Memory legacy = new Memory(52), id = new Memory(24), tag = new Memory(8);
            legacy.clear(); id.clear(); tag.clear();
            boolean legacyOk = NATIVE.GetFileInformationByHandle(handle, legacy);
            int legacyError = legacyOk ? 0 : Native.getLastError();
            boolean idOk = Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo, id, new DWORD(24));
            int idError = idOk ? 0 : Native.getLastError();
            boolean tagOk = Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileAttributeTagInfo, tag, new DWORD(8));
            int tagError = tagOk ? 0 : Native.getLastError();
            char[] finalPath = new char[2048];
            int finalLength = NATIVE.GetFinalPathNameByHandle(handle, finalPath, finalPath.length, 0);
            String finalText = finalLength > 0 && finalLength < finalPath.length ? Native.toString(finalPath) : "error=" + Native.getLastError();
            return "legacyOk=" + legacyOk + "; legacyError=" + legacyError
                    + (legacyOk ? "; legacySerial=" + String.format("%08x", legacy.getInt(28))
                            + "; index64=" + String.format("%08x%08x", legacy.getInt(44), legacy.getInt(48))
                            + "; attributes=" + String.format("%08x", legacy.getInt(0)) + "; links=" + legacy.getInt(40)
                            + "; creation100ns=" + filetime(legacy, 4) + "; access100ns=" + filetime(legacy, 12)
                            + "; mtime100ns=" + filetime(legacy, 20) : "")
                    + "; fileIdInfoOk=" + idOk + "; fileIdInfoError=" + idError
                    + (idOk ? "; serial64=" + String.format("%016x", id.getLong(0))
                            + "; id128Raw=" + HexFormat.of().formatHex(id.getByteArray(8, 16)) : "")
                    + "; tagOk=" + tagOk + "; tagError=" + tagError
                    + (tagOk ? "; tagAttributes=" + String.format("%08x", tag.getInt(0))
                            + "; reparseTag=" + String.format("%08x", tag.getInt(4)) : "") + "; final=" + finalText;
        } finally {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) throw new IOException("CloseHandle failed");
        }
    }

    private static String filetime(Memory memory, int offset) {
        long ticks = Integer.toUnsignedLong(memory.getInt(offset))
                | (Integer.toUnsignedLong(memory.getInt(offset + 4)) << 32);
        return ticks + "(" + Instant.ofEpochSecond(ticks / 10_000_000 - 11_644_473_600L,
                (ticks % 10_000_000) * 100) + ")";
    }

    private static String legacyIdentity(Path path) throws IOException {
        String information = nativeInformation(path);
        return information.substring(information.indexOf("legacySerial="), information.indexOf("; attributes="));
    }

    private static void followup() throws Exception {
        Path tree = Files.createDirectory(ROOT.resolve("Experiment"));
        try {
            Path file = Files.writeString(tree.resolve("Short.bin"), "first");
            capture("long-rename-before", file);
            file = Files.move(file, tree.resolve("A much longer filename with more directory entries.bin"));
            capture("long-rename-after", file);
            Files.delete(file);
            // Check recreation after the short-lived Windows name/metadata cache may have expired.
            Thread.sleep(16000);
            Files.writeString(file, "other", StandardOpenOption.CREATE_NEW);
            capture("delayed-recreate", file);
            long base = Instant.parse("2026-01-15T12:34:56Z").getEpochSecond();
            for (long fraction : new long[] {1, 9_999_999, 10_000_000, 123_456_789, 1_123_456_789L}) {
                Instant requested = Instant.ofEpochSecond(base).plusNanos(fraction);
                setNativeTimes(file, requested);
                capture("native-timestamp-request-" + requested, file);
            }
            reparseAndSeparation(tree);
            for (int i = 0; i < 16; i++) {
                if (!legacyIdentity(file).equals(legacyIdentity(file))) throw new IOException("Unstable reopen control");
            }
            out("reopen-control", "16 pairs of legacy-identity opens equal");
        } finally { removeExperiment(tree); }
        resume();
    }

    private static void setNativeTimes(Path path, Instant instant) throws IOException {
        HANDLE handle = Kernel32.INSTANCE.CreateFile(path.toString(), 0x100, 7, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException("Timestamp open error " + Native.getLastError());
        try {
            Memory time = new Memory(8);
            time.setLong(0, (instant.getEpochSecond() + 11_644_473_600L) * 10_000_000 + instant.getNano() / 100);
            boolean success = NATIVE.SetFileTime(handle, time, time, time);
            out("SetFileTime", "requested=" + instant + "; success=" + success + "; error=" + (success ? 0 : Native.getLastError()));
            if (!success) throw new IOException("SetFileTime error " + Native.getLastError());
        } finally {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) throw new IOException("CloseHandle failed");
        }
    }

    private static void resume() throws IOException {
        var baseline = new Properties();
        try (var input = Files.newInputStream(ROOT.resolve("baseline.properties"))) { baseline.load(input); }
        Path directory = ROOT.resolve("RemountEvidence"), file = directory.resolve("Stable.bin");
        for (var entry : Map.of("root", DRIVE, "directory", directory, "file", file).entrySet()) {
            capture("resume-" + entry.getKey(), entry.getValue());
            String now = legacyIdentity(entry.getValue());
            out("resume-compare-" + entry.getKey(), "before=" + baseline.getProperty(entry.getKey())
                    + "; now=" + now + "; equal=" + now.equals(baseline.getProperty(entry.getKey())));
        }
        out("resume-content", "expectedBytes=" + Files.readString(file).equals("remount evidence")
                + "; mtimeEqual=" + Files.getLastModifiedTime(file).toString().equals(baseline.getProperty("mtime")));
    }

    private static void requireMarker() throws IOException {
        if (!ROOT.equals(ROOT.toAbsolutePath().normalize()) || Files.isSymbolicLink(ROOT)
                || !Files.readString(ROOT.resolve("owner.txt")).equals(MARKER)) throw new IOException("Invalid fixture marker");
    }

    private static void cleanupRetained() throws IOException {
        Files.delete(ROOT.resolve("RemountEvidence").resolve("Stable.bin"));
        Files.delete(ROOT.resolve("RemountEvidence"));
        Files.delete(ROOT.resolve("baseline.properties"));
        Files.delete(ROOT.resolve("owner.txt"));
        Files.delete(ROOT); // Unknown entries prevent removal; no recursive cleanup of retained evidence.
        out("cleanup", "Removed marked remount evidence and fixture root");
    }

    private static void removeExperiment(Path experiment) throws IOException {
        if (!experiment.equals(ROOT.resolve("Experiment")) || !experiment.isAbsolute()) throw new IOException("Invalid cleanup target");
        Files.walkFileTree(experiment, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void out(String label, Object value) { System.out.println(label + "\t" + value); }
}
