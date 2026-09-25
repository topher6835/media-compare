package io.github.topher6835.mediacompare.location;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import io.github.topher6835.mediacompare.location.MacOsDiskutilPlistParser.DiskutilInfo;

/** Dormant host boundary for stable local macOS/APFS context and Source-root evidence capture. */
public final class MacOsApfsContinuityProbe {

    private final Function<LocationPath, ContinuityProbeResult<LocationPath>> resolver;
    private final Function<LocationPath, Path> hostPath;
    private final FileEvidenceReader fileEvidenceReader;
    private final VolumeInspector volumeInspector;
    private final Clock clock;
    private final BooleanSupplier macOs;

    public MacOsApfsContinuityProbe() {
        this(defaultDependencies());
    }

    private MacOsApfsContinuityProbe(DefaultDependencies dependencies) {
        this(
                dependencies.resolver()::resolve,
                dependencies.resolver()::toHostPath,
                new NioFileEvidenceReader(),
                new MountVolumeInspector(new MacOsApfsMountInspector()),
                Clock.systemUTC(),
                MacOsApfsContinuityProbe::isMacOsHost);
    }

    MacOsApfsContinuityProbe(
            Function<LocationPath, ContinuityProbeResult<LocationPath>> resolver,
            Function<LocationPath, Path> hostPath,
            FileEvidenceReader fileEvidenceReader,
            VolumeInspector volumeInspector,
            Clock clock,
            BooleanSupplier macOs) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.hostPath = Objects.requireNonNull(hostPath, "hostPath");
        this.fileEvidenceReader = Objects.requireNonNull(fileEvidenceReader, "fileEvidenceReader");
        this.volumeInspector = Objects.requireNonNull(volumeInspector, "volumeInspector");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.macOs = Objects.requireNonNull(macOs, "macOs");
    }

    public ContinuityProbeResult<MacOsApfsLocationContextEvidence> captureLocationContext(
            LocationPath requestedAnchor) {
        Objects.requireNonNull(requestedAnchor, "requestedAnchor");
        if (!macOs.getAsBoolean()) {
            return ContinuityProbeResult.unsupported();
        }

        ContinuityProbeResult<LocationPath> initialResolution = resolver.apply(requestedAnchor);
        if (initialResolution.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(initialResolution);
        }
        LocationPath exactAnchor = initialResolution.evidence().orElseThrow();
        Path path = hostPath.apply(exactAnchor);

        ContinuityProbeResult<FileObservation> beforeResult = observe(path, false);
        if (beforeResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(beforeResult);
        }
        FileObservation before = beforeResult.evidence().orElseThrow();
        ContinuityProbeResult<DiskutilInfo> firstVolumeResult = volumeInspector.inspect(path);
        if (firstVolumeResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(firstVolumeResult);
        }
        ContinuityProbeResult<DiskutilInfo> secondVolumeResult = volumeInspector.inspect(path);
        if (secondVolumeResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(secondVolumeResult);
        }
        DiskutilInfo firstVolume = firstVolumeResult.evidence().orElseThrow();
        if (!firstVolume.equals(secondVolumeResult.evidence().orElseThrow())) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }
        ContinuityProbeResult<FileObservation> afterResult = observe(path, false);
        if (afterResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(afterResult);
        }
        FileObservation after = afterResult.evidence().orElseThrow();
        if (!before.contextStableWith(after)) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }

        ContinuityProbeResult<LocationPath> finalResolution = resolver.apply(requestedAnchor);
        if (finalResolution.outcome() != ContinuityOutcome.ACCEPTED) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }
        if (!exactAnchor.equals(finalResolution.evidence().orElseThrow())) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }

        try {
            return ContinuityProbeResult.accepted(new MacOsApfsLocationContextEvidence(
                    MacOsApfsLocationContextEvidence.VERSION,
                    MacOsApfsLocationContextEvidence.PROFILE,
                    MacOsApfsLocationContextEvidence.PROFILE_VERSION,
                    exactAnchor,
                    LocationKeyCodec.encode(exactAnchor),
                    firstVolume.fileSystemType(),
                    firstVolume.volumeUuid(),
                    before.inode(),
                    before.directory(),
                    before.symbolicLink(),
                    clock.millis(),
                    new MacOsApfsLocationContextEvidence.Diagnostics(
                            before.unixDevice(), before.fileStoreName(), before.providerClass())));
        } catch (IllegalArgumentException exception) {
            return ContinuityProbeResult.error();
        }
    }

    public ContinuityProbeResult<MacOsApfsSourceRootEvidence> captureSourceRoot(
            MacOsApfsSourceRootProbeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!macOs.getAsBoolean()) {
            return ContinuityProbeResult.unsupported();
        }

        ContinuityProbeResult<LocationPath> initialResolution = resolver.apply(request.requestedRoot());
        if (initialResolution.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(initialResolution);
        }
        LocationPath exactRoot = initialResolution.evidence().orElseThrow();
        if (!request.locationContextEvidence().anchorLocationPath().contains(exactRoot)) {
            return ContinuityProbeResult.uncertain(ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT);
        }
        Path path = hostPath.apply(exactRoot);

        ContinuityProbeResult<FileObservation> beforeResult = observe(path, true);
        if (beforeResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(beforeResult);
        }
        FileObservation before = beforeResult.evidence().orElseThrow();
        ContinuityProbeResult<DiskutilInfo> firstVolumeResult = volumeInspector.inspect(path);
        if (firstVolumeResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(firstVolumeResult);
        }
        ContinuityProbeResult<DiskutilInfo> secondVolumeResult = volumeInspector.inspect(path);
        if (secondVolumeResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(secondVolumeResult);
        }
        DiskutilInfo firstVolume = firstVolumeResult.evidence().orElseThrow();
        if (!firstVolume.equals(secondVolumeResult.evidence().orElseThrow())) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }
        ContinuityProbeResult<FileObservation> afterResult = observe(path, true);
        if (afterResult.outcome() != ContinuityOutcome.ACCEPTED) {
            return copyFailure(afterResult);
        }
        FileObservation after = afterResult.evidence().orElseThrow();
        if (!before.sourceStableWith(after)) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }

        ContinuityProbeResult<LocationPath> finalResolution = resolver.apply(request.requestedRoot());
        if (finalResolution.outcome() != ContinuityOutcome.ACCEPTED
                || !exactRoot.equals(finalResolution.evidence().orElseThrow())) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        }
        if (!request.locationContextEvidence().volumeUuid().equals(firstVolume.volumeUuid())) {
            return ContinuityProbeResult.mismatch(ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH);
        }

        try {
            return ContinuityProbeResult.accepted(new MacOsApfsSourceRootEvidence(
                    MacOsApfsSourceRootEvidence.VERSION,
                    MacOsApfsSourceRootEvidence.PROFILE,
                    MacOsApfsSourceRootEvidence.PROFILE_VERSION,
                    request.locationContextId(),
                    request.locationContextRevision(),
                    request.sourceLocationRevision(),
                    exactRoot,
                    LocationKeyCodec.encode(exactRoot),
                    firstVolume.volumeUuid(),
                    before.inode(),
                    before.birthTime().orElseThrow(),
                    before.directory(),
                    before.symbolicLink(),
                    clock.millis()));
        } catch (IllegalArgumentException exception) {
            return ContinuityProbeResult.error();
        }
    }

    private ContinuityProbeResult<FileObservation> observe(Path path, boolean requireBirthTime) {
        try {
            FileObservation observation = fileEvidenceReader.observe(path);
            if (!observation.directory() || observation.symbolicLink()) {
                return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
            }
            if (requireBirthTime && observation.birthTime().isEmpty()) {
                return ContinuityProbeResult.unavailable();
            }
            return ContinuityProbeResult.accepted(observation);
        } catch (NoSuchFileException exception) {
            return ContinuityProbeResult.unavailable();
        } catch (UnsupportedOperationException exception) {
            return ContinuityProbeResult.unsupported();
        } catch (IOException | SecurityException exception) {
            return ContinuityProbeResult.unavailable();
        } catch (RuntimeException exception) {
            return ContinuityProbeResult.error();
        }
    }

    private static <T, U> ContinuityProbeResult<U> copyFailure(ContinuityProbeResult<T> result) {
        if (result.outcome() == ContinuityOutcome.ACCEPTED) {
            throw new IllegalArgumentException("Cannot copy an accepted result without its evidence");
        }
        return new ContinuityProbeResult<>(result.outcome(), result.reason(), Optional.empty());
    }

    private static DefaultDependencies defaultDependencies() {
        return new DefaultDependencies(new MacOsExactSpellingResolver());
    }

    private static boolean isMacOsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    interface FileEvidenceReader {
        FileObservation observe(Path path) throws IOException;
    }

    interface VolumeInspector {
        ContinuityProbeResult<DiskutilInfo> inspect(Path path);
    }

    record FileObservation(
            String inode,
            Optional<MacOsApfsSourceRootEvidence.BirthTime> birthTime,
            boolean directory,
            boolean symbolicLink,
            String unixDevice,
            String fileStoreName,
            String providerClass) {

        FileObservation {
            Objects.requireNonNull(inode, "inode");
            Objects.requireNonNull(birthTime, "birthTime");
        }

        boolean contextStableWith(FileObservation other) {
            return inode.equals(other.inode)
                    && directory == other.directory
                    && symbolicLink == other.symbolicLink;
        }

        boolean sourceStableWith(FileObservation other) {
            return contextStableWith(other) && birthTime.equals(other.birthTime);
        }
    }

    private static final class NioFileEvidenceReader implements FileEvidenceReader {
        @Override
        public FileObservation observe(Path path) throws IOException {
            BasicFileAttributes basic = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> unix = Files.readAttributes(
                    path, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
            FileStore store = Files.getFileStore(path);
            Instant birth = basic.creationTime().toInstant();
            return new FileObservation(
                    positiveUnsigned(unix.get("ino"), "inode"),
                    Optional.of(new MacOsApfsSourceRootEvidence.BirthTime(
                            birth.getEpochSecond(), birth.getNano())),
                    basic.isDirectory(),
                    basic.isSymbolicLink(),
                    unsigned(unix.get("dev"), "device"),
                    boundedDiagnostic(store.name()),
                    boundedDiagnostic(path.getFileSystem().provider().getClass().getName()));
        }

        private static String positiveUnsigned(Object value, String name) throws IOException {
            String unsigned = unsigned(value, name);
            if ("0".equals(unsigned)) {
                throw new IOException("Required " + name + " was zero");
            }
            return unsigned;
        }

        private static String unsigned(Object value, String name) throws IOException {
            if (!(value instanceof Number number)) {
                throw new IOException("Required Unix " + name + " was not numeric");
            }
            return Long.toUnsignedString(number.longValue());
        }

        private static String boundedDiagnostic(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.getBytes(StandardCharsets.UTF_8).length <= EvidenceValues.MAX_DIAGNOSTIC_UTF8_BYTES
                    ? value
                    : null;
        }
    }

    private static final class MountVolumeInspector implements VolumeInspector {
        private final MacOsApfsMountInspector inspector;

        private MountVolumeInspector(MacOsApfsMountInspector inspector) {
            this.inspector = inspector;
        }

        @Override
        public ContinuityProbeResult<DiskutilInfo> inspect(Path path) {
            ContinuityProbeResult<MacOsApfsMountInspector.MountObservation> result = inspector.inspect(path);
            if (result.outcome() != ContinuityOutcome.ACCEPTED) {
                return copyFailure(result);
            }
            var mount = result.evidence().orElseThrow();
            return ContinuityProbeResult.accepted(
                    new DiskutilInfo(mount.fileSystemType(), mount.volumeUuid()));
        }
    }

    private record DefaultDependencies(MacOsExactSpellingResolver resolver) {
    }
}
