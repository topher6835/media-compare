package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Lazy Windows-only calls; no native initialization during foreign-host projection. */
final class WindowsVolumeNative implements WindowsVolumeProbe.NativeAccess {
    private static final int BUFFER_CHARS = 32768;

    interface Api extends StdCallLibrary {
        boolean GetVolumePathName(String path, char[] root, int length);
        boolean GetVolumeInformation(String root, char[] label, int labelLength, IntByReference serial,
                IntByReference maximum, IntByReference flags, char[] type, int typeLength);
        boolean GetVolumeNameForVolumeMountPoint(String root, char[] guid, int length);
        boolean GetVolumePathNamesForVolumeName(String guid, char[] paths, int length, IntByReference needed);
        int GetDriveType(String root);
        int QueryDosDevice(String device, char[] targets, int length);
        int GetLogicalDriveStrings(int length, char[] drives);
    }

    private static class Library {
        static final Api API = Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);
    }

    @Override
    public WindowsVolumeProbe.NativeVolume observe(Path existingPath) throws IOException {
        Api api = Library.API;
        char[] rootBuffer = new char[BUFFER_CHARS];
        if (!api.GetVolumePathName(existingPath.toString(), rootBuffer, rootBuffer.length)) {
            throw failure("GetVolumePathName");
        }
        String root = terminated(rootBuffer);
        char[] typeBuffer = new char[256];
        IntByReference serial = new IntByReference();
        if (!api.GetVolumeInformation(root, null, 0, serial, new IntByReference(),
                new IntByReference(), typeBuffer, typeBuffer.length)) throw failure("GetVolumeInformation");
        String type = terminated(typeBuffer);
        int driveType = api.GetDriveType(root);
        // NTFS identity remains FileIdInfo-based; stricter route qualification is exFAT-only.
        if (!"exFAT".equalsIgnoreCase(type)) return new WindowsVolumeProbe.NativeVolume(
                type, String.format("%08x", serial.getValue()), null, driveType, root, null, List.of(), false);
        char[] guidBuffer = new char[64];
        if (!api.GetVolumeNameForVolumeMountPoint(root, guidBuffer, guidBuffer.length)) {
            throw failure("GetVolumeNameForVolumeMountPoint");
        }
        String guid = terminated(guidBuffer);
        char[] mounts = new char[BUFFER_CHARS];
        IntByReference needed = new IntByReference();
        if (!api.GetVolumePathNamesForVolumeName(guid, mounts, mounts.length, needed)) {
            throw failure("GetVolumePathNamesForVolumeName");
        }
        if (needed.getValue() <= 0 || needed.getValue() > mounts.length) throw uncertain("Volume paths exceed bound");
        List<String> mountPaths = multiString(mounts, needed.getValue());
        String drive = existingPath.toString().substring(0, 2);
        List<String> devices = queryDevices(api, drive);
        if (devices.size() != 1) throw uncertain("Ambiguous DOS device route");
        char[] driveBuffer = new char[256];
        int count = api.GetLogicalDriveStrings(driveBuffer.length, driveBuffer);
        if (count == 0) throw failure("GetLogicalDriveStrings");
        if (count >= driveBuffer.length) throw uncertain("Logical drive list exceeds bound");
        boolean redirected = false;
        for (String alias : multiString(driveBuffer, count + 1)) {
            if (alias.equalsIgnoreCase(drive + "\\")) continue;
            for (String target : queryDevices(api, alias.substring(0, 2))) {
                String directAlias = "\\??\\" + drive + "\\";
                if (target.equalsIgnoreCase(devices.getFirst())
                        || target.regionMatches(true, 0, directAlias, 0, directAlias.length())) redirected = true;
            }
        }
        return new WindowsVolumeProbe.NativeVolume(type, String.format("%08x", serial.getValue()), guid,
                driveType, root, devices.getFirst(), mountPaths, redirected);
    }

    private static List<String> queryDevices(Api api, String drive) throws IOException {
        char[] buffer = new char[BUFFER_CHARS];
        int length = api.QueryDosDevice(drive, buffer, buffer.length);
        if (length == 0) throw failure("QueryDosDevice");
        if (length > buffer.length) throw uncertain("DOS device list exceeds bound");
        return multiString(buffer, length);
    }

    static String terminated(char[] buffer) throws IOException {
        for (int index = 0; index < buffer.length; index++) {
            if (buffer[index] == 0) return new String(buffer, 0, index);
        }
        throw uncertain("Unterminated native text");
    }

    static List<String> multiString(char[] buffer, int length) throws IOException {
        if (length <= 0 || length > buffer.length) throw uncertain("Invalid native text length");
        var values = new ArrayList<String>();
        int start = 0;
        for (int index = 0; index < length; index++) {
            if (buffer[index] != 0) continue;
            if (index == start) return List.copyOf(values);
            values.add(new String(buffer, start, index - start));
            start = index + 1;
        }
        throw uncertain("Unterminated native string list");
    }

    private static WindowsFileAccessException failure(String operation) {
        int error = Native.getLastError();
        return new WindowsFileAccessException(operation, error);
    }

    private static WindowsFileAccessException uncertain(String message) {
        return new WindowsFileAccessException(WindowsFileAccessException.Reason.UNCERTAIN, message);
    }
}
