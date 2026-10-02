package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Windows path rendering is pure; every access dispatches from fresh filesystem facts. */
public final class WindowsHostFileSystem implements HostFileSystem {
    private final WindowsVolumeProbe volumes;
    private final HostFileSystem ntfs;
    private final WindowsExfatHostFileSystem exfat;

    public WindowsHostFileSystem() {
        this(new WindowsVolumeProbe(), new WindowsNtfsHostFileSystem(), new WindowsExfatHostFileSystem());
    }

    WindowsHostFileSystem(WindowsVolumeProbe volumes, HostFileSystem ntfs, WindowsExfatHostFileSystem exfat) {
        this.volumes = volumes;
        this.ntfs = ntfs;
        this.exfat = exfat;
    }

    @Override public boolean isWindows() { return true; }
    @Override public boolean supportsProfile(FileSystemProfile profile) { return profile == FileSystemProfile.NTFS; }
    @Override public String pathText(LocationPath location) { return ntfs.pathText(location); }
    @Override public FileSystemProfile profile(Path path) throws IOException { return volumes.probe(path).profile(); }

    @Override
    public boolean unsafeElement(Path path, BasicFileAttributes attributes) throws IOException {
        return switch (profile(path)) {
            case NTFS -> ntfs.unsafeElement(path, attributes);
            case EXFAT -> exfat.unsafeElement(path, attributes);
            default -> true;
        };
    }

    @Override
    public HostFileCheck inspect(LocationPath location, long size, Long second, Integer nano) throws IOException {
        final Path file;
        try { file = path(location); }
        catch (IllegalArgumentException failure) { return HostFileCheck.failed(HostFileStatus.UNVERIFIABLE); }
        try {
            return switch (profile(file)) {
                case NTFS -> ntfs.inspect(location, size, second, nano);
                case EXFAT -> exfat.inspect(location, size, second, nano);
                default -> HostFileCheck.failed(HostFileStatus.UNVERIFIABLE);
            };
        } catch (NoSuchFileException failure) { return HostFileCheck.failed(HostFileStatus.MISSING); }
    }

    @Override
    public void requireSessionStorage(Path path) throws IOException {
        if (profile(path) != FileSystemProfile.NTFS) throw new WindowsFileAccessException(
                WindowsFileAccessException.Reason.UNSUPPORTED, "Windows Sessions require NTFS storage");
    }
}
