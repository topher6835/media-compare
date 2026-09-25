package io.github.topher6835.mediacompare.location;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.github.topher6835.mediacompare.location.MacOsDiskutilPlistParser.DiskutilInfo;
import io.github.topher6835.mediacompare.process.BoundedProcessExecutor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** macOS mount-point and APFS identity observation for an arbitrary existing path. */
public final class MacOsApfsMountInspector {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(1);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(2);
    private static final int OUTPUT_LIMIT = 64 * 1024;

    private final BoundedProcessExecutor processes = new BoundedProcessExecutor("media-compare-df-reader-");
    private final MacOsDiskutilRunner diskutil = new MacOsDiskutilRunner();
    private final MacOsDiskutilPlistParser parser = new MacOsDiskutilPlistParser();
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ContinuityProbeResult<MountObservation> inspect(Path path) {
        Objects.requireNonNull(path, "Path");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Mount inspection path must be absolute");
        }
        try {
            MountLocation before = mountLocation(path);
            MacOsDiskutilRunner.Result inspected = diskutil.inspect(Path.of(before.mountPoint()));
            if (!(inspected instanceof MacOsDiskutilRunner.Output output)) {
                return ContinuityProbeResult.unavailable();
            }
            var mounted = parser.parseMounted(output.plist());
            if (!before.mountPoint().equals(mounted.mountPoint())) {
                return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
            }
            MountLocation after = mountLocation(path);
            if (!before.equals(after)) {
                return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
            }
            DiskutilInfo volume = mounted.volume();
            return ContinuityProbeResult.accepted(new MountObservation(
                    before.device(), before.mountPoint(), volume.fileSystemType(), volume.volumeUuid()));
        } catch (MacOsDiskutilPlistParser.UnsupportedFileSystemException exception) {
            return ContinuityProbeResult.unsupported();
        } catch (IllegalArgumentException exception) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        } catch (RuntimeException exception) {
            return ContinuityProbeResult.error();
        }
    }

    private MountLocation mountLocation(Path path) {
        BoundedProcessExecutor.Execution execution = processes.execute(
                List.of("df", "--libxo", "json", path.toString()), null,
                TIMEOUT, TERMINATION_GRACE, CLEANUP_TIMEOUT, OUTPUT_LIMIT, OUTPUT_LIMIT);
        if (!(execution instanceof BoundedProcessExecutor.ExecutionFinished finished)
                || finished.exitCode() != 0) {
            throw new IllegalArgumentException("Could not inspect mounted filesystem");
        }
        JsonNode root = json.readTree(new String(finished.stdout(), StandardCharsets.UTF_8));
        JsonNode rows = root.path("storage-system-information").path("filesystem");
        if (!rows.isArray() || rows.size() != 1) {
            throw new IllegalArgumentException("df returned no single mounted filesystem");
        }
        JsonNode row = rows.get(0);
        String device = requiredText(row, "name");
        String mountPoint = requiredText(row, "mounted-on");
        if (!device.matches("/dev/disk[0-9]+(?:s[0-9]+)*")
                || !Path.of(mountPoint).isAbsolute()) {
            throw new IllegalArgumentException("df returned an unsupported mount identity");
        }
        return new MountLocation(device, mountPoint);
    }

    private static String requiredText(JsonNode row, String key) {
        JsonNode field = row.path(key);
        if (!field.isTextual() || field.asText().isBlank()) {
            throw new IllegalArgumentException("df mount field is missing: " + key);
        }
        return field.asText();
    }

    private record MountLocation(String device, String mountPoint) {
    }

    public record MountObservation(String device, String mountPoint, String fileSystemType,
            String volumeUuid) {
    }
}
