package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Fresh volume facts. Probing never opens an authority window or supplies physical identity. */
public class WindowsVolumeProbe {
    @FunctionalInterface
    interface NativeAccess { NativeVolume observe(Path existingPath) throws IOException; }

    public record NativeVolume(String fileSystemType, String serial, String guid, int driveType,
            String volumeRoot, String dosDevice, List<String> mountPaths, boolean redirectedAlias) {
        public NativeVolume { mountPaths = List.copyOf(mountPaths); }
    }

    public record Result(FileSystemProfile profile, String nioType, NativeVolume nativeVolume,
            boolean ordinaryExfatRoute) { }

    private final NativeAccess nativeAccess;

    public WindowsVolumeProbe() { this(new WindowsVolumeNative()); }

    WindowsVolumeProbe(NativeAccess nativeAccess) { this.nativeAccess = nativeAccess; }

    public Result probe(Path path) throws IOException {
        if (!ordinaryDrivePath(path.toString())) {
            return new Result(FileSystemProfile.UNSUPPORTED, null, null, false);
        }
        Path existing = path;
        while (existing != null && Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) throw new WindowsFileAccessException(
                WindowsFileAccessException.Reason.UNAVAILABLE, "Windows volume path is unavailable");
        String nioType = Files.getFileStore(existing).type();
        return classify(path.toString(), nioType, nativeAccess.observe(existing));
    }

    static Result classify(String path, String nioType, NativeVolume volume) throws IOException {
        FileSystemProfile nativeProfile = FileSystemProfile.fromType(volume.fileSystemType());
        FileSystemProfile nioProfile = FileSystemProfile.fromType(nioType);
        if (nativeProfile == FileSystemProfile.UNKNOWN || nioProfile == FileSystemProfile.UNKNOWN
                || !volume.fileSystemType().equalsIgnoreCase(nioType)) {
            throw new WindowsFileAccessException(WindowsFileAccessException.Reason.UNCERTAIN,
                    "Native and NIO filesystem evidence disagrees");
        }
        FileSystemProfile profile = nativeProfile == FileSystemProfile.NTFS
                || nativeProfile == FileSystemProfile.EXFAT ? nativeProfile : FileSystemProfile.UNSUPPORTED;
        String root = ordinaryDrivePath(path) ? path.substring(0, 2) + "\\" : "";
        boolean ordinary = !root.isEmpty() && (volume.driveType() == 2 || volume.driveType() == 3)
                && root.equalsIgnoreCase(volume.volumeRoot()) && validGuid(volume.guid())
                && volume.serial() != null && volume.serial().matches("[0-9a-f]{8}")
                && volume.dosDevice() != null && volume.dosDevice().startsWith("\\Device\\")
                && !volume.redirectedAlias() && volume.mountPaths().size() == 1
                && root.equalsIgnoreCase(volume.mountPaths().getFirst());
        return new Result(profile, nioType, volume, ordinary);
    }

    static boolean ordinaryDrivePath(String path) {
        String text = path.replace('/', '\\');
        if (!text.matches("(?i)^[a-z]:\\\\.*") || text.indexOf(':', 2) >= 0) return false;
        for (String component : text.substring(3).split("\\\\")) {
            if (component.equals(".") || component.equals("..")) return false;
        }
        return true;
    }

    static boolean validGuid(String guid) {
        return guid != null && guid.matches("(?i)^\\\\\\\\\\?\\\\Volume\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}\\\\$");
    }
}
