package io.github.topher6835.mediacompare.location;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector.MountObservation;

/** Finds the visible boundary of the APFS volume containing an existing Source root. */
public final class MacOsApfsLogicalAnchorResolver {
    private final Function<LocationPath, ContinuityProbeResult<LocationPath>> exactSpelling;
    private final Function<LocationPath, Path> hostPath;
    private final Function<Path, ContinuityProbeResult<MountObservation>> mounts;

    public MacOsApfsLogicalAnchorResolver() {
        this(new MacOsExactSpellingResolver(), new MacOsApfsMountInspector());
    }

    private MacOsApfsLogicalAnchorResolver(MacOsExactSpellingResolver spelling,
            MacOsApfsMountInspector inspector) {
        this(spelling::resolve, spelling::toHostPath, inspector::inspect);
    }

    MacOsApfsLogicalAnchorResolver(
            Function<LocationPath, ContinuityProbeResult<LocationPath>> exactSpelling,
            Function<LocationPath, Path> hostPath,
            Function<Path, ContinuityProbeResult<MountObservation>> mounts) {
        this.exactSpelling = Objects.requireNonNull(exactSpelling, "exactSpelling");
        this.hostPath = Objects.requireNonNull(hostPath, "hostPath");
        this.mounts = Objects.requireNonNull(mounts, "mounts");
    }

    public ContinuityProbeResult<LocationPath> resolve(LocationPath requestedRoot) {
        Objects.requireNonNull(requestedRoot, "requestedRoot");
        if (requestedRoot.dialect() != LocationDialect.UNIX) {
            return ContinuityProbeResult.unsupported();
        }

        try {
            ContinuityProbeResult<LocationPath> resolution = exactSpelling.apply(requestedRoot);
            if (resolution.outcome() != ContinuityOutcome.ACCEPTED) {
                return copyFailure(resolution);
            }
            LocationPath exactRoot = resolution.evidence().orElseThrow();
            if (exactRoot.dialect() != LocationDialect.UNIX
                    || exactRoot.components().size() != requestedRoot.components().size()) {
                return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
            }

            Map<LocationPath, MountObservation> observed = new LinkedHashMap<>();
            LocationPath current = exactRoot;
            LocationPath anchor = exactRoot;
            MountObservation sourceMount = null;
            while (true) {
                ContinuityProbeResult<MountObservation> result = mounts.apply(hostPath.apply(current));
                if (result.outcome() != ContinuityOutcome.ACCEPTED) {
                    return copyFailure(result);
                }
                MountObservation mount = result.evidence().orElseThrow();
                if (!MacOsApfsLocationContextEvidence.FILE_SYSTEM_TYPE.equals(mount.fileSystemType())
                        || !EvidenceValues.canonicalUuid(mount.volumeUuid(), "APFS Volume UUID")
                                .equals(mount.volumeUuid())) {
                    return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
                }
                observed.put(current, mount);
                if (sourceMount == null) {
                    sourceMount = mount;
                } else if (!sourceMount.volumeUuid().equals(mount.volumeUuid())) {
                    break;
                } else if (!sourceMount.device().equals(mount.device())
                        || !sourceMount.mountPoint().equals(mount.mountPoint())) {
                    return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
                } else {
                    anchor = current;
                }
                if (current.components().isEmpty()) {
                    break;
                }
                List<String> components = current.components();
                current = new LocationPath(LocationDialect.UNIX, List.of(),
                        components.subList(0, components.size() - 1));
            }

            for (Map.Entry<LocationPath, MountObservation> entry : observed.entrySet()) {
                ContinuityProbeResult<MountObservation> repeated =
                        mounts.apply(hostPath.apply(entry.getKey()));
                if (repeated.outcome() != ContinuityOutcome.ACCEPTED) {
                    return copyFailure(repeated);
                }
                if (!entry.getValue().equals(repeated.evidence().orElseThrow())) {
                    return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
                }
            }
            ContinuityProbeResult<LocationPath> finalResolution = exactSpelling.apply(requestedRoot);
            if (finalResolution.outcome() != ContinuityOutcome.ACCEPTED
                    || !exactRoot.equals(finalResolution.evidence().orElseThrow())) {
                return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
            }
            return ContinuityProbeResult.accepted(anchor);
        } catch (IllegalArgumentException exception) {
            return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
        } catch (RuntimeException exception) {
            return ContinuityProbeResult.error();
        }
    }

    private static <T> ContinuityProbeResult<LocationPath> copyFailure(ContinuityProbeResult<T> result) {
        return new ContinuityProbeResult<>(result.outcome(), result.reason(), Optional.empty());
    }
}
