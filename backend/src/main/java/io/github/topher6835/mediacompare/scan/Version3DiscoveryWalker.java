package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.HostFileStatus;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsPathInspector;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.scan.authority.ChildStorageBoundary;
import io.github.topher6835.mediacompare.scan.authority.ResolvedFileCandidate;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityOutcome;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthoritySnapshot;
import io.github.topher6835.mediacompare.scan.authority.ScanFileObservation;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;
import io.github.topher6835.mediacompare.scan.authority.TraversalCompletion;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Traverses one v3 Source while checking every directory against its accepted mount. */
@Component
public class Version3DiscoveryWalker {
    private final Function<Path, ContinuityProbeResult<MacOsApfsMountInspector.MountObservation>> inspectMount;

    @Autowired
    public Version3DiscoveryWalker() {
        this(new MacOsApfsMountInspector()::inspect);
    }

    public Version3DiscoveryWalker(
            Function<Path, ContinuityProbeResult<MacOsApfsMountInspector.MountObservation>> inspectMount) {
        this.inspectMount = inspectMount;
    }

    public TraversalCompletion.Issue walk(Path root, ScanAuthoritySnapshot authority,
            Consumer<ResolvedFileCandidate> observer) throws IOException {
        if (authority.windowsNtfs()) return walkWindows(root, authority, observer);
        var anchor = inspectMount.apply(hostPath(authority.contextAnchor()));
        var source = inspectMount.apply(root);
        if (anchor.outcome() != ContinuityOutcome.ACCEPTED
                || source.outcome() != ContinuityOutcome.ACCEPTED) {
            return TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE;
        }
        var acceptedMount = anchor.evidence().orElseThrow();
        var rootMount = source.evidence().orElseThrow();
        if (!acceptedMount.mountPoint().equals(rootMount.mountPoint())
                || !acceptedMount.device().equals(rootMount.device())
                || !authority.contextBaseline().volumeUuid().equals(rootMount.volumeUuid())) {
            return TraversalCompletion.Issue.UNSUPPORTED_CHILD_STORAGE;
        }

        TraversalCompletion.Issue[] issue = { TraversalCompletion.Issue.COMPLETE };
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                IndexingInterruptedException.check();
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                    recordIssue(issue, TraversalCompletion.Issue.SYMBOLIC_LINK_AMBIGUITY);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                var observed = inspectMount.apply(directory);
                if (observed.outcome() != ContinuityOutcome.ACCEPTED) {
                    recordIssue(issue, observed.outcome() == ContinuityOutcome.UNSUPPORTED
                            ? TraversalCompletion.Issue.UNSUPPORTED_CHILD_STORAGE
                            : TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                var current = observed.evidence().orElseThrow();
                if (!acceptedMount.mountPoint().equals(current.mountPoint())
                        || !acceptedMount.device().equals(current.device())
                        || !acceptedMount.volumeUuid().equals(current.volumeUuid())) {
                    recordIssue(issue, TraversalCompletion.Issue.UNSUPPORTED_CHILD_STORAGE);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                IndexingInterruptedException.check();
                if (attributes.isSymbolicLink()) {
                    recordIssue(issue, TraversalCompletion.Issue.SYMBOLIC_LINK_AMBIGUITY);
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                LocationPath fileLocation = exactLocation(authority.sourceRoot(), root.relativize(file));
                Instant modified = attributes.lastModifiedTime().toInstant();
                var result = ScanObservationAuthority.resolve(authority, new ScanFileObservation(
                        authority.sourceId(), authority.sourceRevision(), authority.contextId(),
                        authority.contextRevision(), fileLocation, LocationKeyCodec.encode(fileLocation),
                        acceptedMount.fileSystemType(), acceptedMount.volumeUuid(),
                        ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false,
                        attributes.size(), modified.getEpochSecond(), modified.getNano()));
                if (result.outcome() == ScanAuthorityOutcome.TRUSTED) {
                    observer.accept(result.value().orElseThrow());
                } else {
                    recordIssue(issue, result.outcome() == ScanAuthorityOutcome.UNSUPPORTED
                            ? TraversalCompletion.Issue.UNSUPPORTED_CHILD_STORAGE
                            : TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                recordIssue(issue, TraversalCompletion.Issue.INACCESSIBLE_SUBTREE);
                return FileVisitResult.CONTINUE;
            }
        });
        return issue[0];
    }

    private TraversalCompletion.Issue walkWindows(Path root, ScanAuthoritySnapshot authority,
            Consumer<ResolvedFileCandidate> observer) throws IOException {
        var anchor = WindowsNtfsPathInspector.inspect(authority.contextAnchor(), true);
        var source = WindowsNtfsPathInspector.inspect(authority.sourceRoot(), true);
        if (anchor.status() != HostFileStatus.ESTABLISHED
                || source.status() != HostFileStatus.ESTABLISHED
                || !authority.volumeId().equals(anchor.identity().volumeSerial())
                || !authority.volumeId().equals(source.identity().volumeSerial())) {
            return TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE;
        }
        TraversalCompletion.Issue[] issue = { TraversalCompletion.Issue.COMPLETE };
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                IndexingInterruptedException.check();
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                    recordIssue(issue, TraversalCompletion.Issue.SYMBOLIC_LINK_AMBIGUITY);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                try {
                    LocationPath location = exactLocation(authority.sourceRoot(), root.relativize(directory));
                    var observed = WindowsNtfsPathInspector.inspect(location, true);
                    if (observed.status() != HostFileStatus.ESTABLISHED
                            || !authority.volumeId().equals(observed.identity().volumeSerial())) {
                        recordIssue(issue, TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                } catch (IllegalArgumentException exception) {
                    recordIssue(issue, TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                IndexingInterruptedException.check();
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    recordIssue(issue, TraversalCompletion.Issue.SYMBOLIC_LINK_AMBIGUITY);
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                try {
                    LocationPath location = exactLocation(authority.sourceRoot(), root.relativize(file));
                    var observed = WindowsNtfsPathInspector.inspect(location, false);
                    if (observed.status() != HostFileStatus.ESTABLISHED
                            || !authority.volumeId().equals(observed.identity().volumeSerial())) {
                        recordIssue(issue, TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                        return FileVisitResult.CONTINUE;
                    }
                    Instant modified = attributes.lastModifiedTime().toInstant();
                    var result = ScanObservationAuthority.resolve(authority, new ScanFileObservation(
                            authority.sourceId(), authority.sourceRevision(), authority.contextId(),
                            authority.contextRevision(), location, LocationKeyCodec.encode(location),
                            "ntfs", observed.identity().volumeSerial(),
                            ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false,
                            attributes.size(), modified.getEpochSecond(), modified.getNano()));
                    if (result.outcome() == ScanAuthorityOutcome.TRUSTED) {
                        observer.accept(result.value().orElseThrow());
                    } else {
                        recordIssue(issue, TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                    }
                } catch (IllegalArgumentException exception) {
                    recordIssue(issue, TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                recordIssue(issue, TraversalCompletion.Issue.INACCESSIBLE_SUBTREE);
                return FileVisitResult.CONTINUE;
            }
        });
        return issue[0];
    }

    private static LocationPath exactLocation(LocationPath root, Path relative) {
        LocationPath location = root;
        for (Path segment : relative) {
            location = location.append(segment.toString());
        }
        return location;
    }

    private static Path hostPath(LocationPath location) {
        return HostFileSystems.current().path(location);
    }

    private static void recordIssue(TraversalCompletion.Issue[] current,
            TraversalCompletion.Issue next) {
        if (current[0] == TraversalCompletion.Issue.COMPLETE) {
            current[0] = next;
        }
    }
}
