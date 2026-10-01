package io.github.topher6835.mediacompare.matching;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Function;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.analysis.StaleContentHashException;
import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.CurrentMembershipAuthority;
import io.github.topher6835.mediacompare.filesystem.HostFileCheck;
import io.github.topher6835.mediacompare.filesystem.HostFileStatus;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsPathInspector;
import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector;
import io.github.topher6835.mediacompare.scan.Version3AuthorityCapture;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityOutcome;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthoritySnapshot;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static io.github.topher6835.mediacompare.matching.CleanupPreflightReason.*;

/** Fresh continuity, exact path/storage evidence and bytes; never publishes anything. */
@Component
public class CleanupPreflightFileValidator {
    private final Version3AuthorityCapture capture;
    private final ContentHashFileHasher hasher;
    private final Function<Path, ContinuityProbeResult<MacOsApfsMountInspector.MountObservation>> mounts;

    @Autowired
    public CleanupPreflightFileValidator(Version3AuthorityCapture capture, ContentHashFileHasher hasher) {
        this(capture, hasher, new MacOsApfsMountInspector()::inspect);
    }

    public CleanupPreflightFileValidator(Version3AuthorityCapture capture, ContentHashFileHasher hasher,
            Function<Path, ContinuityProbeResult<MacOsApfsMountInspector.MountObservation>> mounts) {
        this.capture = capture;
        this.hasher = hasher;
        this.mounts = mounts;
    }

    public CleanupPreflightReason validate(CleanupPreflightCatalog.PhysicalFile file, String digest) {
        return validatePhysicalFile(file, digest).reason();
    }

    /** The same live route/path checks without digest hashing, for a single-file host action. */
    public ValidatedFile validateForReveal(CleanupPreflightCatalog.PhysicalFile file) {
        return validatePhysicalFile(file, null);
    }

    private ValidatedFile validatePhysicalFile(CleanupPreflightCatalog.PhysicalFile file, String digest) {
        FileEntry entry = file.entry();
        if (!"RESOLVED".equals(entry.locationIdentityStatus())) return failed(UNSAFE_PATH);
        final LocationPath location;
        try {
            location = new LocationPathCodec().decode(entry.locationPath());
            if (location.components().isEmpty()
                    || !LocationKeyCodec.matches(location, LocationKey.parse(entry.locationKey()))) {
                return failed(UNSAFE_PATH);
            }
        } catch (IllegalArgumentException exception) {
            return failed(UNSAFE_PATH);
        }
        CleanupPreflightReason failure = AUTHORITY_UNAVAILABLE;
        for (var route : file.routes()) {
            var member = route.membership();
            var source = route.source();
            var context = route.context();
            if (!CurrentMembershipAuthority.isCurrentRoute(entry, member, source, context)) continue;
            var start = capture.capture(source, context);
            if (start.outcome() != ScanAuthorityOutcome.TRUSTED) {
                failure = start.outcome() == ScanAuthorityOutcome.STALE ? AUTHORITY_CHANGED : AUTHORITY_UNAVAILABLE;
                continue;
            }
            var authority = start.value().orElseThrow();
            if (!authority.contextAnchor().contains(location) || !authority.sourceRoot().contains(location)
                    || authority.sourceRoot().equals(location)
                    || !member.relativePath().equals(SourceRelativePath.from(authority.sourceRoot(), location))
                    || !member.pathKey().equals(member.relativePath())) {
                failure = UNSAFE_PATH;
                continue;
            }
            try {
                Path path = hostPath(location);
                HostFileCheck fileBefore = HostFileSystems.current().inspect(location, entry.sizeBytes(),
                        entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano());
                var fileFailure = fileFailure(fileBefore.status());
                if (fileFailure != null) return failed(fileFailure);
                var before = checkPath(entry, path, authority);
                if (before != null) return failed(before);
                if (entry.currentContentId() == null) return failed(AUTHORITY_CHANGED);
                String actual = null;
                if (digest != null) {
                    actual = hasher.hash(new ContentHashCandidate(entry.id(), entry.currentContentId(),
                            member.id(), source.id(), context.id(), context.revision(), member.membershipRevision(),
                            entry.locationPath(), entry.locationKey(), entry.observationRevision(), entry.sizeBytes(),
                            entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano(), source.locationRevision()));
                }
                var after = checkPath(entry, path, authority);
                if (after != null) return failed(after);
                HostFileCheck fileAfter = HostFileSystems.current().inspect(location, entry.sizeBytes(),
                        entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano());
                fileFailure = fileFailure(fileAfter.status());
                if (fileFailure != null) return failed(fileFailure);
                if (!fileBefore.sameFileAs(fileAfter)) return failed(FILESYSTEM_CHANGED);
                var end = capture.capture(source, context);
                if (end.outcome() != ScanAuthorityOutcome.TRUSTED) {
                    failure = AUTHORITY_CHANGED;
                    continue;
                }
                var last = end.value().orElseThrow();
                if (!authority.sameRootAs(last) || !authority.sameContextAs(last)) {
                    failure = AUTHORITY_CHANGED;
                    continue;
                }
                return digest == null || actual.equals(digest)
                        ? new ValidatedFile(path, null) : failed(HASH_MISMATCH);
            } catch (StaleContentHashException exception) {
                return failed(FILESYSTEM_CHANGED);
            } catch (IOException | SecurityException | UnsupportedOperationException exception) {
                return failed(IO_UNAVAILABLE);
            }
        }
        return failed(failure);
    }

    private static ValidatedFile failed(CleanupPreflightReason reason) {
        return new ValidatedFile(null, reason);
    }

    private static CleanupPreflightReason fileFailure(HostFileStatus status) {
        return switch (status) {
            case ESTABLISHED -> null;
            case MISSING -> IO_UNAVAILABLE;
            case STALE -> FILESYSTEM_CHANGED;
            case UNVERIFIABLE -> AUTHORITY_UNAVAILABLE;
            case UNSAFE_PATH -> UNSAFE_PATH;
        };
    }

    public record ValidatedFile(Path path, CleanupPreflightReason reason) { }

    private CleanupPreflightReason checkPath(FileEntry entry, Path file, ScanAuthoritySnapshot authority)
            throws IOException {
        if (authority.windowsNtfs()) {
            var observed = WindowsNtfsPathInspector.inspect(
                    new LocationPathCodec().decode(entry.locationPath()), false);
            if (observed.status() == HostFileStatus.UNSAFE_PATH) return UNSAFE_PATH;
            if (observed.status() != HostFileStatus.ESTABLISHED) return AUTHORITY_UNAVAILABLE;
            return authority.volumeId().equals(observed.identity().volumeSerial()) ? null : AUTHORITY_CHANGED;
        }
        Path anchor = hostPath(authority.contextAnchor());
        // Inspect ancestors in order before probing any descendant path.
        java.util.List<Path> storagePaths = new java.util.ArrayList<>();
        Path current = file.getRoot();
        var rootAttributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!rootAttributes.isDirectory() || HostFileSystems.current().unsafeElement(current, rootAttributes)) return UNSAFE_PATH;
        for (Path segment : file) {
            current = current.resolve(segment);
            var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (HostFileSystems.current().unsafeElement(current, attrs) || (!current.equals(file) && !attrs.isDirectory())
                    || (current.equals(file) && !attrs.isRegularFile())) return UNSAFE_PATH;
            if (current.startsWith(anchor)) storagePaths.add(current);
            if (current.equals(file)) {
                var modified = attrs.lastModifiedTime().toInstant();
                if (attrs.size() != entry.sizeBytes() || entry.modifiedTimeEpochSecond() == null
                        || entry.modifiedTimeNano() == null
                        || modified.getEpochSecond() != entry.modifiedTimeEpochSecond()
                        || modified.getNano() != entry.modifiedTimeNano()) return FILESYSTEM_CHANGED;
            }
        }
        var accepted = mounts.apply(anchor);
        if (accepted.outcome() != ContinuityOutcome.ACCEPTED) return AUTHORITY_UNAVAILABLE;
        var baseline = accepted.evidence().orElseThrow();
        if (!"apfs".equals(baseline.fileSystemType())
                || !authority.contextBaseline().volumeUuid().equals(baseline.volumeUuid())) return AUTHORITY_CHANGED;
        for (Path storagePath : storagePaths) {
            var observed = mounts.apply(storagePath);
            if (observed.outcome() != ContinuityOutcome.ACCEPTED) return AUTHORITY_UNAVAILABLE;
            var mount = observed.evidence().orElseThrow();
            if (!baseline.mountPoint().equals(mount.mountPoint()) || !baseline.device().equals(mount.device())
                    || !baseline.volumeUuid().equals(mount.volumeUuid())
                    || !"apfs".equals(mount.fileSystemType())) return AUTHORITY_CHANGED;
        }
        return null;
    }

    private static Path hostPath(LocationPath location) {
        return HostFileSystems.current().path(location);
    }
}
