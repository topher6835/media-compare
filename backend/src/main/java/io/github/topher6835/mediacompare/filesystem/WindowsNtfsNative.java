package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;

/** Windows-only handle observation. No native handle or JNA value escapes this adapter. */
public final class WindowsNtfsNative {
    private WindowsNtfsNative() { }

    public static Observation observe(Path path) throws IOException {
        HANDLE handle = Kernel32.INSTANCE.CreateFile(path.toString(), 0,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
                null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new IOException("Cannot open Windows filesystem identity (error "
                    + Kernel32.INSTANCE.GetLastError() + ")");
        }
        try {
            Memory tag = new Memory(8);
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileAttributeTagInfo,
                    tag, new DWORD(8))) {
                throw new IOException("Cannot inspect Windows file attributes (error "
                        + Kernel32.INSTANCE.GetLastError() + ")");
            }
            int attributes = tag.getInt(0);
            boolean directory = (attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0;
            if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                return new Observation(null, directory, true);
            }
            Memory id = new Memory(24);
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo,
                    id, new DWORD(24))) {
                throw new IOException("Cannot inspect Windows file ID (error "
                        + Kernel32.INSTANCE.GetLastError() + ")");
            }
            WindowsNtfsIdentity identity = new WindowsNtfsIdentity(
                    String.format("%016x", id.getLong(0)), HexFormat.of().formatHex(id.getByteArray(8, 16)));
            return new Observation(identity, directory, false);
        } finally {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw new IOException("Cannot close Windows filesystem identity handle");
            }
        }
    }

    public record Observation(WindowsNtfsIdentity identity, boolean directory, boolean reparsePoint) { }
}
