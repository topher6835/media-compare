import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

/** Manual experiment only. Never starts Spring, opens a catalog, or manages VeraCrypt. */
public class WindowsExfatRetainedHandleProbe {
    static final Path DRIVE = Path.of("Z:\\");
    static final Path ROOT = DRIVE.resolve("media-compare-retained-handle-test");
    static final Path SOURCE = ROOT.resolve("Source"), MID = SOURCE.resolve("Intermediate");
    static final Path A = MID.resolve("ProtectedA.bin"), B = MID.resolve("ProtectedB.bin");
    static final Path DEST = ROOT.resolve("MoveTarget");
    static final String MARKER = "Media Compare disposable retained-handle diagnostic v1\n";
    static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    static BufferedWriter log;
    static Path control;
    static String volumeGuid;
    static byte[] bytesA, bytesB;

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("guards", "run", "retry", "resume", "cleanup").contains(args[0]))
            throw new IllegalArgumentException("Usage: ... guards|run|retry|resume|cleanup evidenceLog controlDirectory");
        Path evidence = Path.of(args[1]).toAbsolutePath().normalize();
        control = Path.of(args[2]).toAbsolutePath().normalize();
        Path repo = Path.of("").toAbsolutePath().normalize();
        require(evidence.startsWith(repo.resolve("docs/evidence")), "Evidence must be under repository docs/evidence");
        require(control.startsWith(repo.resolve("backend/target")), "Control must be under ignored backend/target");
        require(Files.getFileStore(repo).type().equalsIgnoreCase("NTFS"), "Repository-side evidence must be NTFS");
        StandardOpenOption create = Set.of("retry", "resume", "cleanup").contains(args[0])
                ? StandardOpenOption.APPEND : StandardOpenOption.CREATE_NEW;
        try (BufferedWriter writer = Files.newBufferedWriter(evidence, create, StandardOpenOption.WRITE)) {
            log = writer;
            out("begin", "mode=" + args[0] + "; pid=" + ProcessHandle.current().pid()
                    + "; cwd=" + repo + "; os=" + System.getProperty("os.name")
                    + "; java=" + System.getProperty("java.version"));
            try {
                guardVolume();
                fixtureBytes();
                switch (args[0]) {
                    case "guards" -> out("guards-only", "fixtureExists=" + Files.exists(ROOT, NOFOLLOW));
                    case "run", "retry" -> run(); // Retry still refuses an existing fixture; never reuses it.
                    case "resume" -> resume();
                    case "cleanup" -> cleanup();
                }
                out("end", "success=true; trackedFileDirectoryHandles=" + RetainedHandleNative.openCount);
            } catch (Exception failure) {
                out("FAILED/STOP", failure.toString() + "; trackedFileDirectoryHandles=" + RetainedHandleNative.openCount);
                throw failure;
            }
        }
    }

    static synchronized void out(String event, Object value) {
        String line = Instant.now() + "\t" + event + "\t" + value;
        System.out.println(line);
        if (log != null) {
            try { log.write(line); log.newLine(); log.flush(); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    static void guardVolume() throws IOException {
        out("section", "initial guards; no fixture mutation yet");
        require(System.getProperty("os.name").startsWith("Windows"), "Windows required");
        require(Files.isDirectory(DRIVE, NOFOLLOW), "Z: missing");
        char[] device = new char[1024], label = new char[256], fs = new char[256], guid = new char[1024];
        int count = RetainedHandleNative.API.QueryDosDevice("Z:", device, device.length);
        int deviceError = count == 0 ? Native.getLastError() : 0;
        require(count != 0, "QueryDosDevice error=" + deviceError);
        var serial = new IntByReference(); var maximum = new IntByReference(); var flags = new IntByReference();
        var nativeResult = RetainedHandleNative.Result.capture(RetainedHandleNative.API.GetVolumeInformation(
                DRIVE.toString(), label, label.length, serial, maximum, flags, fs, fs.length));
        require(nativeResult.success(), "GetVolumeInformation: " + nativeResult);
        FileStore store = Files.getFileStore(DRIVE);
        String actual = String.format("%08x", serial.getValue());
        out("volume", "device=" + Native.toString(device) + "; nativeFs=" + Native.toString(fs)
                + "; nioFs=" + store.type() + "; serial=" + actual + "; expected=98d16f05"
                + "; label=" + Native.toString(label) + "; flags=" + String.format("%08x", flags.getValue())
                + "; maximumComponent=" + maximum.getValue() + "; readOnly=" + store.isReadOnly());
        require(actual.equals("98d16f05") && Native.toString(fs).equalsIgnoreCase("exFAT")
                && store.type().equalsIgnoreCase("exFAT")
                && Native.toString(device).equals("\\Device\\VeraCryptVolumeZ"), "Wrong test volume; abort before mutation");
        var guidResult = RetainedHandleNative.Result.capture(RetainedHandleNative.API.GetVolumeNameForVolumeMountPoint(
                DRIVE.toString(), guid, guid.length));
        require(guidResult.success(), "Volume GUID: " + guidResult);
        volumeGuid = Native.toString(guid);
        out("volume-guid", volumeGuid);
        out("guards", "PASS; Windows/Z:/native exFAT/NIO exFAT/serial/device mapping");
    }

    static void fixtureBytes() throws IOException {
        bytesA = bitmap(0x112233); bytesB = bitmap(0x998877);
        require(bytesA.length == bytesB.length && !Arrays.equals(bytesA, bytesB), "Distinct equal-size fixture bytes");
    }

    static byte[] bitmap(int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, color);
        var output = new ByteArrayOutputStream();
        require(ImageIO.write(image, "BMP", output), "JDK BMP writer unavailable");
        require(output.size() <= 256, "Unexpected BMP size");
        return Arrays.copyOf(output.toByteArray(), 256);
    }

    static void run() throws Exception {
        require(!Files.exists(ROOT, NOFOLLOW), "Fixture already exists; refusing reuse: " + ROOT);
        Files.createDirectory(ROOT);
        Files.writeString(ROOT.resolve("owner.txt"), MARKER, StandardOpenOption.CREATE_NEW);
        Files.createDirectory(SOURCE); Files.createDirectory(MID); Files.createDirectory(DEST);
        Files.write(A, bytesA, StandardOpenOption.CREATE_NEW); Files.write(B, bytesB, StandardOpenOption.CREATE_NEW);
        out("fixture", "created exact marked fixture=" + ROOT + "; A/B each=256 bytes; disposable BMP payloads");
        out("known-bytes", "A.sha256=" + RetainedHandleNative.sha(bytesA) + "; B.sha256=" + RetainedHandleNative.sha(bytesB));
        captureNio(A); captureNio(B);
        directoryTest();
        fileTest();
        conflictingAccessTest();
        require(RetainedHandleNative.openCount == 0, "Pre-checkpoint native handle leak");
        checkpoint();
    }

    static void requireMarker() throws IOException {
        require(ROOT.isAbsolute() && ROOT.equals(ROOT.normalize()) && !Files.isSymbolicLink(ROOT), "Invalid fixture path");
        require(Files.isRegularFile(ROOT.resolve("owner.txt"), NOFOLLOW)
                && Files.readString(ROOT.resolve("owner.txt")).equals(MARKER), "Ownership marker missing/changed");
        for (Path path : List.of(ROOT, SOURCE, MID, DEST)) {
            if (Files.exists(path, NOFOLLOW)) {
                var attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
                require(attrs.isDirectory() && !attrs.isOther() && !attrs.isSymbolicLink(), "Unsafe fixture route: " + path);
            }
        }
    }

    static void captureNio(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        out("nio", "path=" + path + "; real=" + path.toRealPath(NOFOLLOW) + "; size=" + attrs.size()
                + "; directory=" + attrs.isDirectory() + "; regular=" + attrs.isRegularFile()
                + "; other=" + attrs.isOther() + "; link=" + attrs.isSymbolicLink() + "; key=" + attrs.fileKey()
                + "; created=" + attrs.creationTime() + "; access=" + attrs.lastAccessTime() + "; mtime=" + attrs.lastModifiedTime());
    }

    static void directoryTest() throws Exception {
        out("section", "A/E directory-chain protection; independently opened pathname operations in same process");
        try (var drive = RetainedHandleNative.directory(DRIVE); var fixture = RetainedHandleNative.directory(ROOT);
             var source = RetainedHandleNative.directory(SOURCE); var intermediate = RetainedHandleNative.directory(MID)) {
            var beforeSource = checked(source, true); var beforeMid = checked(intermediate, true);
            checked(drive, true); checked(fixture, true);
            for (Path path : List.of(SOURCE, MID)) {
                blocked("directory-rename " + path, move(path, path.resolveSibling(path.getFileName() + "Renamed"), false));
                blocked("directory-move " + path, move(path, DEST.resolve(path.getFileName()), false));
                var deletion = RetainedHandleNative.Result.capture(RetainedHandleNative.API.RemoveDirectory(path.toString()));
                out("nonempty-directory-delete", "path=" + path + "; " + deletion + "; empty-directory test follows");
                require(!deletion.success(), "Protected nonempty directory deleted");
                require(Files.isDirectory(path, NOFOLLOW), "Protected pathname changed");
            }
            require(beforeSource.sameObjectFields(checked(source, true)) && beforeMid.sameObjectFields(checked(intermediate, true)),
                    "Directory handle observations contradicted baseline");
            require(Arrays.equals(Files.readAllBytes(A), bytesA) && Arrays.equals(Files.readAllBytes(B), bytesB), "Directory tests changed bytes");
            // Empty directories isolate deletion sharing from ERROR_DIR_NOT_EMPTY.
            requireMarker(); Files.delete(A); Files.delete(B);
            blocked("empty-intermediate-delete", RetainedHandleNative.Result.capture(RetainedHandleNative.API.RemoveDirectory(MID.toString())));
            checked(intermediate, true);
            intermediate.close();
            success("empty-intermediate-delete-after-close", RetainedHandleNative.Result.capture(RetainedHandleNative.API.RemoveDirectory(MID.toString())));
            blocked("empty-source-delete", RetainedHandleNative.Result.capture(RetainedHandleNative.API.RemoveDirectory(SOURCE.toString())));
            checked(source, true);
            source.close();
            success("empty-source-delete-after-close", RetainedHandleNative.Result.capture(RetainedHandleNative.API.RemoveDirectory(SOURCE.toString())));
        }
        Files.createDirectory(SOURCE); Files.createDirectory(MID);
        Files.write(A, bytesA, StandardOpenOption.CREATE_NEW); Files.write(B, bytesB, StandardOpenOption.CREATE_NEW);
        for (Path path : List.of(SOURCE, MID)) {
            Path rename = path.resolveSibling(path.getFileName() + "Renamed"), moved = DEST.resolve(path.getFileName());
            success("directory-rename-after-close", move(path, rename, false)); success("directory-rename-restore", move(rename, path, false));
            success("directory-move-after-close", move(path, moved, false)); success("directory-move-restore", move(moved, path, false));
        }
        out("A-result", "PASS; protected rename/move/empty-delete blocked; unprotected controls succeeded; handles closed");
    }

    static RetainedHandleNative.Result move(Path from, Path to, boolean replace) throws IOException {
        require(from.normalize().startsWith(ROOT) && to.normalize().startsWith(ROOT), "Move outside exact fixture refused");
        return RetainedHandleNative.Result.capture(RetainedHandleNative.API.MoveFileEx(from.toString(), to.toString(), replace ? 1 : 0));
    }

    static void blocked(String label, RetainedHandleNative.Result result) throws IOException {
        out("protected-attempt", label + "; " + result);
        require(!result.success() && (result.error() == 32 || result.error() == 5),
                "Expected denial (sharing violation 32 or access denied 5): " + label + "; " + result);
    }

    static void success(String label, RetainedHandleNative.Result result) throws IOException {
        out("unprotected-control", label + "; " + result);
        require(result.success(), "Control failed: " + label + "; " + result);
    }

    static RetainedHandleNative.Observation checked(RetainedHandleNative.Held held, boolean directory) throws IOException {
        var observation = held.observe();
        out("retained-handle", "path=" + held.path + "; native=" + Pointer.nativeValue(held.handle.getPointer()) + "; " + observation);
        require(observation.serial().equals("98d16f05") && ((observation.attributes() & 0x10) != 0) == directory
                && (observation.attributes() & 0x400) == 0
                && observation.finalPath().equals("\\\\?\\" + held.path), "Handle classification/volume/final-path contradiction");
        return observation;
    }

    static RetainedHandleNative.Result writableAttempt(boolean truncate) throws IOException {
        try (var writable = RetainedHandleNative.open(A, RetainedHandleNative.WRITE, RetainedHandleNative.SHARE_ALL,
                truncate ? RetainedHandleNative.TRUNCATE_EXISTING : RetainedHandleNative.OPEN_EXISTING, false)) {
            var count = new IntByReference();
            var result = RetainedHandleNative.Result.capture(RetainedHandleNative.API.WriteFile(writable.handle, bytesB, bytesB.length, count, null));
            out("WriteFile", "truncate=" + truncate + "; " + result + "; bytesWritten=" + count.getValue());
            return result;
        } catch (RetainedHandleNative.OpenFailure failure) { return new RetainedHandleNative.Result(false, failure.error); }
    }

    static void fileTest() throws Exception {
        out("section", "B/D/E protected file sharing, readers, retained-handle SHA-256 and before/after evidence");
        // Align replacement candidate mtime before trying equal-size/equal-mtime replacement.
        Files.setLastModifiedTime(A, java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-15T12:34:56Z")));
        Files.setLastModifiedTime(B, Files.getLastModifiedTime(A));
        captureNio(A); captureNio(B);
        try (Chain chain = new Chain(); var file = RetainedHandleNative.protectedFile(A)) {
            var before = checked(file, false);
            for (String operation : List.of("overwrite", "truncate-write", "delete", "rename", "move", "equal-size-mtime-replace")) {
                blocked(operation, fileMutation(operation));
                require(Files.exists(A, NOFOLLOW) && Files.exists(B, NOFOLLOW)
                        && !Files.exists(A.resolveSibling("Renamed.bin"), NOFOLLOW) && !Files.exists(DEST.resolve("Moved.bin"), NOFOLLOW),
                        "Protected file path changed");
                var after = checked(file, false);
                require(before.sameObjectFields(after) && before.modified() == after.modified() && before.creation() == after.creation(),
                        "Protected file observation changed");
                require(Arrays.equals(file.readAll(), bytesA), "Protected bytes changed");
                chain.check();
            }
            byte[] retained = file.readAll();
            out("same-retained-handle-hash", "native=" + Pointer.nativeValue(file.handle.getPointer())
                    + "; count=" + retained.length + "; sha256=" + RetainedHandleNative.sha(retained)
                    + "; expected=" + RetainedHandleNative.sha(bytesA));
            require(Arrays.equals(retained, bytesA), "Retained handle did not read original bytes");
            try (var reader = RetainedHandleNative.open(A, RetainedHandleNative.READ, RetainedHandleNative.SHARE_ALL,
                    RetainedHandleNative.OPEN_EXISTING, false)) {
                byte[] bytes = reader.readAll();
                out("compatible-native-reader", "count=" + bytes.length + "; sha256=" + RetainedHandleNative.sha(bytes));
                require(Arrays.equals(bytes, retained), "Compatible native reader mismatch");
            }
            try (var stream = Files.newInputStream(A, StandardOpenOption.READ)) {
                byte[] bytes = stream.readAllBytes();
                out("ordinary-nio-reader", "count=" + bytes.length + "; sha256=" + RetainedHandleNative.sha(bytes));
                require(Arrays.equals(bytes, retained), "Ordinary reader mismatch");
            }
            BufferedImage decoded = ImageIO.read(A.toFile());
            out("compatible-jdk-decoder", "ImageIO pathname BMP read; width=" + (decoded == null ? -1 : decoded.getWidth())
                    + "; height=" + (decoded == null ? -1 : decoded.getHeight()) + "; RGB=" + (decoded == null ? -1 : decoded.getRGB(0, 0)));
            require(decoded != null && decoded.getWidth() == 2 && (decoded.getRGB(0, 0) & 0xffffff) == 0x112233, "Decoder result mismatch");
            require(Arrays.equals(file.readAll(), bytesA) && before.sameObjectFields(checked(file, false)), "After-reader contradiction");
        }
        for (String operation : List.of("overwrite", "truncate-write", "delete", "rename", "move", "equal-size-mtime-replace")) {
            success(operation + "-after-close", fileMutation(operation));
            if (operation.equals("rename")) success("restore-renamed-file", move(A.resolveSibling("Renamed.bin"), A, false));
            if (operation.equals("move")) success("restore-moved-file", move(DEST.resolve("Moved.bin"), A, false));
            Files.write(A, bytesA); Files.write(B, bytesB);
        }
        out("B/D/E-result", "PASS; six mutations blocked; six controls succeeded; native/NIO/decoder readers coexist; same-handle hash matched");
    }

    static RetainedHandleNative.Result fileMutation(String operation) throws IOException {
        return switch (operation) {
            case "overwrite" -> writableAttempt(false);
            case "truncate-write" -> writableAttempt(true);
            case "delete" -> RetainedHandleNative.Result.capture(RetainedHandleNative.API.DeleteFile(A.toString()));
            case "rename" -> move(A, A.resolveSibling("Renamed.bin"), false);
            case "move" -> move(A, DEST.resolve("Moved.bin"), false);
            case "equal-size-mtime-replace" -> move(B, A, true);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    static void conflictingAccessTest() throws Exception {
        out("section", "C pre-existing separately opened writer and writable mapping");
        try (var writer = RetainedHandleNative.open(A, RetainedHandleNative.WRITE, RetainedHandleNative.SHARE_ALL,
                RetainedHandleNative.OPEN_EXISTING, false)) {
            rejectProtectedAcquisition("pre-existing-writer");
        }
        try (var writer = RetainedHandleNative.open(A, RetainedHandleNative.READ | RetainedHandleNative.WRITE,
                RetainedHandleNative.SHARE_ALL, RetainedHandleNative.OPEN_EXISTING, false)) {
            HANDLE mapping = RetainedHandleNative.API.CreateFileMapping(writer.handle, null, 4, 0, 0, null); // PAGE_READWRITE
            int mappingError = RetainedHandleNative.invalid(mapping) ? Native.getLastError() : 0;
            out("CreateFileMapping", "PAGE_READWRITE; success=" + !RetainedHandleNative.invalid(mapping) + "; error=" + mappingError);
            require(!RetainedHandleNative.invalid(mapping), "Writable mapping could not be tested; error=" + mappingError);
            Pointer view = null;
            try {
                view = RetainedHandleNative.API.MapViewOfFile(mapping, 2, 0, 0, new SIZE_T(bytesA.length)); // FILE_MAP_WRITE
                int viewError = view == null ? Native.getLastError() : 0;
                out("MapViewOfFile", "FILE_MAP_WRITE; success=" + (view != null) + "; error=" + viewError);
                require(view != null, "Writable view could not be tested");
                rejectProtectedAcquisition("writable-map-with-writer-handle");
                writer.close(); // Critical control: retain writable mapping/view without the original file handle.
                try (var acquired = RetainedHandleNative.protectedFile(A)) {
                    out("mapping-conflict", "protected acquisition unexpectedly succeeded with live writable view and closed writer handle");
                    byte before = view.getByte(0); view.setByte(0, (byte) (before ^ 1));
                    var flushed = RetainedHandleNative.Result.capture(RetainedHandleNative.API.FlushViewOfFile(view, new SIZE_T(bytesA.length)));
                    byte[] now = acquired.readAll();
                    out("mapping-write-demonstration", "flush=" + flushed + "; originalSha=" + RetainedHandleNative.sha(bytesA)
                            + "; protectedSha=" + RetainedHandleNative.sha(now) + "; originalBytes=" + Arrays.equals(now, bytesA));
                    throw new IOException("Authority mechanism failed: writable mapped access coexists with protected open");
                } catch (RetainedHandleNative.OpenFailure failure) {
                    blocked("writable-map-after-writer-handle-close", new RetainedHandleNative.Result(false, failure.error));
                }
            } finally {
                if (view != null) success("UnmapViewOfFile", RetainedHandleNative.Result.capture(RetainedHandleNative.API.UnmapViewOfFile(view)));
                success("CloseHandle-mapping", RetainedHandleNative.Result.capture(RetainedHandleNative.API.CloseHandle(mapping)));
            }
        }
        try (var fresh = RetainedHandleNative.protectedFile(A)) {
            require(Arrays.equals(fresh.readAll(), bytesA), "Writer/mapping test altered expected bytes");
            out("C-control", "protected acquisition succeeds after closing writer/mapping/view; hash=" + RetainedHandleNative.sha(fresh.readAll()));
        }
        out("C-result", "PASS; conflicting writer and writable view rejected; all mapping/writer controls closed");
    }

    static void rejectProtectedAcquisition(String label) throws IOException {
        try (var unexpected = RetainedHandleNative.protectedFile(A)) {
            throw new IOException("Protected acquisition unexpectedly succeeded: " + label);
        } catch (RetainedHandleNative.OpenFailure failure) {
            blocked(label, new RetainedHandleNative.Result(false, failure.error));
        }
    }

    static final class Chain implements AutoCloseable {
        final List<RetainedHandleNative.Held> handles = new ArrayList<>();
        Chain() throws IOException {
            try {
                for (Path path : List.of(DRIVE, ROOT, SOURCE, MID)) {
                    var handle = RetainedHandleNative.directory(path); handles.add(handle); checked(handle, true);
                }
            } catch (IOException failure) { close(); throw failure; }
        }
        void check() throws IOException { for (var handle : handles) checked(handle, true); }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (int i = handles.size() - 1; i >= 0; i--) {
                try { handles.get(i).close(); } catch (IOException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
            }
            if (failure != null) throw failure;
        }
    }

    static void checkpoint() throws Exception {
        out("section", "F pre-dismount checkpoint; diagnostic never dismounts anything");
        String window = UUID.randomUUID().toString();
        var baseline = new Properties();
        baseline.setProperty("window", window); baseline.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        baseline.setProperty("fixture", ROOT.toString()); baseline.setProperty("volumeGuid", volumeGuid);
        baseline.setProperty("shaA", RetainedHandleNative.sha(bytesA)); baseline.setProperty("shaB", RetainedHandleNative.sha(bytesB));
        Instant deadline = Instant.now().plus(Duration.ofMinutes(90));
        baseline.setProperty("deadline", deadline.toString());
        try (Chain chain = new Chain(); var a = RetainedHandleNative.protectedFile(A); var b = RetainedHandleNative.protectedFile(B)) {
            chain.check(); checked(a, false); checked(b, false);
            baseline.setProperty("beforeA", a.observe().toString()); baseline.setProperty("beforeB", b.observe().toString());
            writeState(baseline, "HELD");
            out("READY FOR MANUAL VERACRYPT DISMOUNT TEST", "pid=" + ProcessHandle.current().pid() + "; window=" + window
                    + "; handles=6 (Z:\\,fixture,Source,Intermediate,A,B); directory share=READ|WRITE; file share=READ"
                    + "; deadline=" + deadline + "; command file=" + control.resolve("command.txt"));
            while (Instant.now().isBefore(deadline)) {
                Path commandFile = control.resolve("command.txt");
                if (Files.exists(commandFile)) {
                    String command = Files.readString(commandFile).trim().toLowerCase(Locale.ROOT);
                    Files.delete(commandFile);
                    out("manual-control", "command=" + command + "; no dismount/remount is performed by this diagnostic");
                    if (command.equals("release")) { out("window-revoked", window + "; explicit release; no renewal"); break; }
                    if (!command.equals("observe")) { out("control-rejected", command); continue; }
                    try {
                        guardVolume(); require(volumeGuid.equals(baseline.getProperty("volumeGuid")), "Mount-route mismatch");
                        chain.check(); checked(a, false); checked(b, false);
                        require(Arrays.equals(a.readAll(), bytesA) && Arrays.equals(b.readAll(), bytesB), "Retained byte mismatch after user action");
                        out("post-user-action-observation", "retained handles remain coherent on current route; no remount/continuity inferred");
                    } catch (IOException unavailable) {
                        out("post-user-action-loss", unavailable.toString() + "; window revoked; closing every old handle"); break;
                    }
                }
                Thread.sleep(1000);
            }
            out("window-ending", window + "; explicit release/loss or bounded 90-minute timeout; old UUID never renewed");
        } finally { writeState(baseline, "DEAD"); }
        require(RetainedHandleNative.openCount == 0, "Checkpoint close leaked handles");
        out("released", "all six retained handles closed; fixture/baseline retained for user-controlled normal dismount/remount");
    }

    static void writeState(Properties state, String phase) throws IOException {
        state.setProperty("phase", phase); state.setProperty("stateAt", Instant.now().toString());
        Path temporary = control.resolve("checkpoint.tmp");
        try (var output = Files.newOutputStream(temporary)) { state.store(output, "Disposable diagnostic history, never an authority credential"); }
        Files.move(temporary, control.resolve("checkpoint.properties"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Properties deadCheckpoint() throws IOException {
        Properties baseline = new Properties();
        try (var input = Files.newInputStream(control.resolve("checkpoint.properties"))) { baseline.load(input); }
        require(baseline.getProperty("phase").equals("DEAD"), "Old window must be released before fresh acquisition/cleanup");
        require(ProcessHandle.of(Long.parseLong(baseline.getProperty("pid"))).map(p -> !p.isAlive()).orElse(true), "Old diagnostic process still alive");
        require(ROOT.toString().equals(baseline.getProperty("fixture")), "Checkpoint fixture mismatch");
        return baseline;
    }

    static void resume() throws Exception {
        out("section", "G fresh guarded acquisition after user-controlled remount; no automatic replay");
        requireMarker(); Properties baseline = deadCheckpoint();
        require(RetainedHandleNative.openCount == 0, "Fresh process must start without retained authority handles");
        out("old-window-check", "window=" + baseline.getProperty("window")
                + "; phase=DEAD; prior process is dead; live handles=0; serialized checkpoint is comparison history only");
        String newWindow = UUID.randomUUID().toString();
        require(!newWindow.equals(baseline.getProperty("window")), "New runtime window UUID required");
        try (Chain chain = new Chain(); var a = RetainedHandleNative.protectedFile(A); var b = RetainedHandleNative.protectedFile(B)) {
            var beforeA = checked(a, false); var beforeB = checked(b, false);
            chain.check(); captureNio(A); captureNio(B);
            byte[] currentA = a.readAll(), currentB = b.readAll();
            require(Arrays.equals(currentA, bytesA) && Arrays.equals(currentB, bytesB), "Remount fixture content mismatch");
            out("post-remount-bytes", "A.count=" + currentA.length + "; A.base64=" + Base64.getEncoder().encodeToString(currentA)
                    + "; B.count=" + currentB.length + "; B.base64=" + Base64.getEncoder().encodeToString(currentB) + "; expectedBytes=true");
            out("empirical-before-after-A", "before=" + baseline.getProperty("beforeA") + "; now=" + beforeA
                    + "; equal=" + beforeA.toString().equals(baseline.getProperty("beforeA")) + "; never an authority test");
            out("empirical-before-after-B", "before=" + baseline.getProperty("beforeB") + "; now=" + beforeB
                    + "; equal=" + beforeB.toString().equals(baseline.getProperty("beforeB")) + "; never an authority test");
            var afterA = checked(a, false); var afterB = checked(b, false); chain.check();
            require(beforeA.sameObjectFields(afterA) && beforeB.sameObjectFields(afterB)
                    && beforeA.modified() == afterA.modified() && beforeB.modified() == afterB.modified(), "Fresh-read observation changed");
            out("fresh-acquisition", "oldWindow=" + baseline.getProperty("window") + "; oldPhase=DEAD; newWindow=" + newWindow
                    + "; new guarded native handles; hashA=" + RetainedHandleNative.sha(currentA) + "; hashB=" + RetainedHandleNative.sha(currentB)
                    + "; priorGuid=" + baseline.getProperty("volumeGuid") + "; currentGuid=" + volumeGuid);
        }
        out("fresh-window-closed", newWindow + "; all fresh handles closed; historical UUIDs grant no authority");
    }

    static void cleanup() throws Exception {
        out("section", "cleanup exact marked fixture; no recursive deletion");
        requireMarker();
        if (Files.exists(control.resolve("checkpoint.properties"))) deadCheckpoint();
        else {
            long oldPid = Long.parseLong(Files.readString(control.resolve("process.pid")).trim());
            require(ProcessHandle.of(oldPid).map(p -> !p.isAlive()).orElse(true), "Aborted diagnostic still alive");
            out("aborted-run-cleanup", "no checkpoint created; prior process=" + oldPid + " is dead; require exact marker/occupants");
        }
        // Enumerate all occupants before deletion, reject links/unknown content, and use named deletes only.
        Map<Path, Set<String>> allowed = Map.of(ROOT, Set.of("owner.txt", "Source", "MoveTarget"),
                SOURCE, Set.of("Intermediate"), MID, Set.of("ProtectedA.bin", "ProtectedB.bin"), DEST, Set.of());
        for (var entry : allowed.entrySet()) {
            try (var stream = Files.newDirectoryStream(entry.getKey())) {
                for (Path child : stream) require(entry.getValue().contains(child.getFileName().toString())
                        && !Files.isSymbolicLink(child) && !Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW).isOther(),
                        "Unknown/unsafe fixture occupant; cleanup refused: " + child);
            }
        }
        for (Path path : List.of(A, B, MID, SOURCE, DEST, ROOT.resolve("owner.txt"), ROOT)) {
            Files.delete(path); out("cleanup-delete", path);
        }
        out("cleanup-result", "fixtureExists=" + Files.exists(ROOT, NOFOLLOW) + "; no recursive deletion; no Source/catalog/media touched");
    }
}
