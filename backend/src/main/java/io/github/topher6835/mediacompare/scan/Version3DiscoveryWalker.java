package io.github.topher6835.mediacompare.scan;

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

    private static LocationPath exactLocation(LocationPath root, Path relative) {
        LocationPath location = root;
        for (Path segment : relative) {
            location = location.append(segment.toString());
        }
        return location;
    }

    private static Path hostPath(LocationPath location) {
        Path path = Path.of("/");
        for (String component : location.components()) {
            path = path.resolve(component);
        }
        return path;
    }

    private static void recordIssue(TraversalCompletion.Issue[] current,
            TraversalCompletion.Issue next) {
        if (current[0] == TraversalCompletion.Issue.COMPLETE) {
            current[0] = next;
        }
    }
}
