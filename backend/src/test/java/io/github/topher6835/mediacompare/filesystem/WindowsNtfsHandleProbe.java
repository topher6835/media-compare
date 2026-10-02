package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Path;

/** Isolated process for the native test's process-wide handle count; never writes fixtures. */
public final class WindowsNtfsHandleProbe {
    private WindowsNtfsHandleProbe() { }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]), folder = file.getParent(), drive = file.getRoot();
        var expectedFile = WindowsNtfsNative.observe(file);
        var expectedFolder = WindowsNtfsNative.observe(folder);
        var expectedDrive = WindowsNtfsNative.observe(drive);
        for (int index = 0; index < 64; index++) observe(file, folder, drive, expectedFile, expectedFolder, expectedDrive);
        int before = WindowsNtfsNativeTests.handleCount();
        for (int index = 0; index < 512; index++) observe(file, folder, drive, expectedFile, expectedFolder, expectedDrive);
        int afterSuccess = WindowsNtfsNativeTests.handleCount();
        if (afterSuccess > before + 8) throw new AssertionError("Successful observations leaked handles: " + before + " -> " + afterSuccess);
        for (int index = 0; index < 64; index++) {
            try {
                WindowsNtfsNative.observe(folder.resolve("missing"));
                throw new AssertionError("Missing fixture unexpectedly opened");
            } catch (IOException expected) { }
        }
        int afterFailures = WindowsNtfsNativeTests.handleCount();
        if (afterFailures > before + 8) throw new AssertionError("Failed observations leaked handles: " + before + " -> " + afterFailures);
        System.out.println("isolated handles before=" + before + ", after successful opens=" + afterSuccess + ", after failures=" + afterFailures);
    }

    private static void observe(Path file, Path folder, Path drive, WindowsNtfsNative.Observation expectedFile,
            WindowsNtfsNative.Observation expectedFolder, WindowsNtfsNative.Observation expectedDrive) throws IOException {
        if (!expectedFile.equals(WindowsNtfsNative.observe(file))
                || !expectedFolder.equals(WindowsNtfsNative.observe(folder))
                || !expectedDrive.equals(WindowsNtfsNative.observe(drive))) throw new AssertionError("Native identity changed");
    }
}
