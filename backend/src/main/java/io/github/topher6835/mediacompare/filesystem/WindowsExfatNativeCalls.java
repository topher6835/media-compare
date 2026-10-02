package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Path;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** JNA values stay inside this implementation; methods return only Java facts/errors. */
final class WindowsExfatNativeCalls implements WindowsExfatNative.Calls {
    interface Api extends StdCallLibrary {
        HANDLE CreateFile(String path, int access, int share, Pointer security, int disposition,
                int flags, HANDLE template);
        boolean GetHandleInformation(HANDLE handle, IntByReference flags);
        boolean GetFileInformationByHandle(HANDLE handle, Pointer information);
        int GetFinalPathNameByHandle(HANDLE handle, char[] text, int length, int flags);
        boolean SetFilePointerEx(HANDLE handle, long distance, LongByReference position, int method);
        boolean ReadFile(HANDLE handle, byte[] bytes, int count, IntByReference read, Pointer overlapped);
        boolean CloseHandle(HANDLE handle);
    }

    private static class Library {
        static final Api API = Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);
    }

    private record OwnedHandle(HANDLE value) implements WindowsExfatNative.NativeHandle { }

    private final Api injected;
    WindowsExfatNativeCalls() { injected = null; }
    WindowsExfatNativeCalls(Api injected) { this.injected = injected; }
    private Api api() { return injected == null ? Library.API : injected; }
    private HANDLE handle(WindowsExfatNative.NativeHandle handle) { return ((OwnedHandle) handle).value(); }

    @Override
    public WindowsExfatNative.NativeHandle open(Path path, int access, int share, int disposition, int flags)
            throws IOException {
        HANDLE handle = api().CreateFile(path.toString(), access, share, null, disposition, flags, null);
        if (handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == -1) {
            throw failure("CreateFile");
        }
        return new OwnedHandle(handle);
    }

    @Override
    public int handleFlags(WindowsExfatNative.NativeHandle handle) throws IOException {
        IntByReference flags = new IntByReference();
        if (!api().GetHandleInformation(handle(handle), flags)) throw failure("GetHandleInformation");
        return flags.getValue();
    }

    @Override
    public WindowsExfatNativeAccess.Observation observe(WindowsExfatNative.NativeHandle handle) throws IOException {
        try (Memory info = new Memory(52)) {
            if (!api().GetFileInformationByHandle(handle(handle), info)) throw failure("GetFileInformationByHandle");
            long size = (Integer.toUnsignedLong(info.getInt(32)) << 32) | Integer.toUnsignedLong(info.getInt(36));
            long creation = info.getLong(4), access = info.getLong(12), modified = info.getLong(20);
            if (size < 0 || creation < 0 || access < 0 || modified < 0) throw uncertain("Native size/time exceeds supported range");
            return new WindowsExfatNativeAccess.Observation(info.getInt(0), String.format("%08x", info.getInt(28)),
                    String.format("%08x%08x", info.getInt(44), info.getInt(48)), size,
                    creation, access, modified, finalPath(handle));
        }
    }

    private String finalPath(WindowsExfatNative.NativeHandle handle) throws IOException {
        int capacity = 512;
        for (int attempt = 0; attempt < 4; attempt++) {
            char[] text = new char[capacity];
            int length = api().GetFinalPathNameByHandle(handle(handle), text, text.length, 0);
            if (length == 0) throw failure("GetFinalPathNameByHandle");
            if (length < 0 || length >= 32768) throw uncertain("Final path exceeds bound");
            if (length < text.length) {
                if (text[length] != 0) throw uncertain("Unterminated final path");
                String path = new String(text, 0, length);
                if (path.indexOf('\0') >= 0) throw uncertain("Embedded null in final path");
                return path;
            }
            capacity = Math.addExact(length, 1);
        }
        throw uncertain("Final path length did not stabilize");
    }

    @Override
    public long seek(WindowsExfatNative.NativeHandle handle, long position) throws IOException {
        LongByReference actual = new LongByReference();
        if (!api().SetFilePointerEx(handle(handle), position, actual, 0)) throw failure("SetFilePointerEx");
        return actual.getValue();
    }

    @Override
    public int read(WindowsExfatNative.NativeHandle handle, byte[] bytes, int count) throws IOException {
        IntByReference read = new IntByReference();
        if (!api().ReadFile(handle(handle), bytes, count, read, null)) throw failure("ReadFile");
        return read.getValue();
    }

    @Override
    public void close(WindowsExfatNative.NativeHandle handle) throws IOException {
        if (!api().CloseHandle(handle(handle))) throw failure("CloseHandle");
    }

    private static WindowsFileAccessException failure(String operation) {
        int error = Native.getLastError();
        return new WindowsFileAccessException(operation, error);
    }

    private static WindowsFileAccessException uncertain(String message) {
        return new WindowsFileAccessException(WindowsFileAccessException.Reason.UNCERTAIN, message);
    }
}
