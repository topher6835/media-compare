import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Win32 calls for the standalone experiment only; no production authority. */
final class RetainedHandleNative {
    static final int READ = 0x80000000, WRITE = 0x40000000;
    static final int SHARE_READ = 1, SHARE_WRITE = 2, SHARE_DELETE = 4, SHARE_ALL = 7;
    static final int OPEN_EXISTING = 3, TRUNCATE_EXISTING = 5;
    static final int BACKUP = 0x02000000, NOFOLLOW = 0x00200000;
    static int openCount;
    static final Api API = Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);

    interface Api extends StdCallLibrary {
        HANDLE CreateFile(String path, int access, int share, Pointer security, int disposition,
                          int flags, HANDLE template);
        boolean CloseHandle(HANDLE handle);
        boolean GetHandleInformation(HANDLE handle, IntByReference flags);
        boolean GetFileInformationByHandle(HANDLE handle, Pointer info);
        int GetFinalPathNameByHandle(HANDLE handle, char[] path, int size, int flags);
        boolean GetVolumeInformation(String root, char[] label, int labelSize, IntByReference serial,
                                     IntByReference maximum, IntByReference flags, char[] fs, int fsSize);
        boolean GetVolumeNameForVolumeMountPoint(String root, char[] name, int size);
        int QueryDosDevice(String device, char[] target, int size);
        boolean SetFilePointerEx(HANDLE handle, long distance, LongByReference position, int method);
        boolean ReadFile(HANDLE handle, byte[] buffer, int count, IntByReference read, Pointer overlapped);
        boolean WriteFile(HANDLE handle, byte[] buffer, int count, IntByReference written, Pointer overlapped);
        boolean DeleteFile(String path);
        boolean RemoveDirectory(String path);
        boolean MoveFileEx(String from, String to, int flags);
        boolean SetFileTime(HANDLE handle, Pointer creation, Pointer access, Pointer modified);
        HANDLE CreateFileMapping(HANDLE file, Pointer security, int protection, int high, int low, String name);
        Pointer MapViewOfFile(HANDLE mapping, int access, int high, int low, SIZE_T size);
        boolean UnmapViewOfFile(Pointer view);
        boolean FlushViewOfFile(Pointer view, SIZE_T size);
    }

    record Result(boolean success, int error) {
        static Result capture(boolean success) { return new Result(success, success ? 0 : Native.getLastError()); }
    }

    record Observation(int attributes, String serial, String index, long size,
                       long creation, long access, long modified, String finalPath) {
        boolean sameObjectFields(Observation other) {
            return attributes == other.attributes && serial.equals(other.serial)
                    && index.equals(other.index) && finalPath.equals(other.finalPath) && size == other.size;
        }
        @Override public String toString() {
            return "attributes=" + String.format("%08x", attributes) + "; serial=" + serial
                    + "; index64=" + index + "; size=" + size + "; creation100ns=" + creation
                    + "(" + fileTime(creation) + "); access100ns=" + access + "(" + fileTime(access)
                    + "); mtime100ns=" + modified + "(" + fileTime(modified) + "); final=" + finalPath;
        }
    }

    static final class Held implements AutoCloseable {
        final Path path;
        final HANDLE handle;
        private boolean closed;

        Held(Path path, HANDLE handle) { this.path = path; this.handle = handle; openCount++; }

        Observation observe() throws IOException {
            if (closed) throw new IOException("Closed handle is never reused: " + path);
            Memory info = new Memory(52);
            Result result = Result.capture(API.GetFileInformationByHandle(handle, info));
            if (!result.success()) throw new IOException("GetFileInformationByHandle " + path + ": " + result);
            char[] finalPath = new char[4096];
            int length = API.GetFinalPathNameByHandle(handle, finalPath, finalPath.length, 0);
            int error = length == 0 ? Native.getLastError() : 0;
            if (length == 0 || length >= finalPath.length) throw new IOException("Final path error=" + error);
            return new Observation(info.getInt(0), String.format("%08x", info.getInt(28)),
                    String.format("%08x%08x", info.getInt(44), info.getInt(48)),
                    ((long) info.getInt(32) << 32) | Integer.toUnsignedLong(info.getInt(36)),
                    info.getLong(4), info.getLong(12), info.getLong(20), Native.toString(finalPath));
        }

        byte[] readAll() throws IOException {
            if (closed) throw new IOException("Closed handle is never read: " + path);
            Result seek = Result.capture(API.SetFilePointerEx(handle, 0, new LongByReference(), 0));
            if (!seek.success()) throw new IOException("SetFilePointerEx: " + seek);
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                IntByReference read = new IntByReference();
                Result result = Result.capture(API.ReadFile(handle, buffer, buffer.length, read, null));
                if (!result.success()) throw new IOException("ReadFile: " + result);
                if (read.getValue() == 0) break;
                output.write(buffer, 0, read.getValue());
                if (output.size() > 4096) throw new IOException("Fixture read exceeded known bound");
            }
            return output.toByteArray();
        }

        @Override public void close() throws IOException {
            if (!closed) {
                Result result = Result.capture(API.CloseHandle(handle));
                if (!result.success()) throw new IOException("CloseHandle " + path + ": " + result);
                closed = true;
                openCount--;
                WindowsExfatRetainedHandleProbe.out("close", "path=" + path + "; native="
                        + Pointer.nativeValue(handle.getPointer()) + "; " + result + "; remaining=" + openCount);
            }
        }
    }

    static boolean invalid(HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == -1;
    }

    static Held open(Path path, int access, int share, int disposition, boolean directory) throws IOException {
        HANDLE handle = API.CreateFile(path.toString(), access, share, null, disposition,
                NOFOLLOW | (directory ? BACKUP : 0), null);
        int error = invalid(handle) ? Native.getLastError() : 0;
        WindowsExfatRetainedHandleProbe.out("CreateFile", "path=" + path + "; access="
                + String.format("%08x", access) + "; share=" + share + "; disposition=" + disposition
                + "; flags=" + String.format("%08x", NOFOLLOW | (directory ? BACKUP : 0))
                + "; security=NULL/non-inheritable; success=" + !invalid(handle) + "; error=" + error);
        if (invalid(handle)) throw new OpenFailure(error, path);
        Held held = new Held(path, handle);
        try {
            IntByReference flags = new IntByReference();
            Result result = Result.capture(API.GetHandleInformation(handle, flags));
            WindowsExfatRetainedHandleProbe.out("handle-flags", "path=" + path + "; " + result
                    + "; flags=" + flags.getValue() + "; native=" + Pointer.nativeValue(handle.getPointer()));
            if (!result.success() || (flags.getValue() & 1) != 0) throw new IOException("Inheritable/uncertain handle");
            return held;
        } catch (IOException failure) { held.close(); throw failure; }
    }

    static Held directory(Path path) throws IOException {
        return open(path, READ, SHARE_READ | SHARE_WRITE, OPEN_EXISTING, true);
    }

    static Held protectedFile(Path path) throws IOException {
        return open(path, READ, SHARE_READ, OPEN_EXISTING, false);
    }

    static final class OpenFailure extends IOException {
        final int error;
        OpenFailure(int error, Path path) { super("CreateFile failed error=" + error + ": " + path); this.error = error; }
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Instant fileTime(long value) {
        long unix100ns = value - 116444736000000000L;
        return Instant.ofEpochSecond(Math.floorDiv(unix100ns, 10_000_000), Math.floorMod(unix100ns, 10_000_000) * 100);
    }
}
