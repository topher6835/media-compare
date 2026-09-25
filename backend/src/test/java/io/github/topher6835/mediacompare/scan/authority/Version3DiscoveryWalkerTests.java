package io.github.topher6835.mediacompare.scan.authority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector.MountObservation;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.scan.Version3DiscoveryWalker;

class Version3DiscoveryWalkerTests {
    private static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String VOLUME_ID = "11111111-2222-3333-4444-555555555555";
    private static final LocationPath ANCHOR = path("/Volumes/Archive");
    private static final MountObservation ACCEPTED = new MountObservation(
            "/dev/disk2s1", "/Volumes/Archive", "apfs", VOLUME_ID);

    @TempDir Path directory;

    @Test
    void completeTraversalPublishesExactRelativeCandidates() throws Exception {
        Path root = Files.createDirectory(directory.resolve("source"));
        Files.writeString(root.resolve("top.jpg"), "top");
        Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve("nested").resolve("deep.jpg"), "deep");
        var candidates = new ArrayList<ResolvedFileCandidate>();
        var walker = new Version3DiscoveryWalker(
                ignored -> ContinuityProbeResult.accepted(ACCEPTED));
        assertEquals(TraversalCompletion.Issue.COMPLETE,
                walker.walk(root, snapshot(), candidates::add));
        assertEquals(2, candidates.size());
        assertEquals(java.util.List.of("nested/deep.jpg", "top.jpg"), candidates.stream()
                .map(ResolvedFileCandidate::relativePath).sorted().toList());
    }

    @Test
    void sameUuidChildMountDoesNotAuthorizeMissingOrChildCandidate() throws Exception {
        Path root = Files.createDirectory(directory.resolve("source"));
        Files.writeString(root.resolve("top.jpg"), "top");
        Path child = Files.createDirectory(root.resolve("child-mount"));
        Files.writeString(child.resolve("inside.jpg"), "inside");
        var candidates = new ArrayList<ResolvedFileCandidate>();
        var walker = new Version3DiscoveryWalker(path -> ContinuityProbeResult.accepted(
                path.equals(child) ? new MountObservation(
                        "/dev/disk3s1", child.toString(), "apfs", VOLUME_ID) : ACCEPTED));
        assertEquals(TraversalCompletion.Issue.UNSUPPORTED_CHILD_STORAGE,
                walker.walk(root, snapshot(), candidates::add));
        assertEquals(java.util.List.of("top.jpg"), candidates.stream()
                .map(ResolvedFileCandidate::relativePath).toList());
    }

    @Test
    void uncertainChildBoundaryFailsClosed() throws Exception {
        Path root = Files.createDirectory(directory.resolve("source"));
        Path child = Files.createDirectory(root.resolve("uncertain"));
        Files.writeString(child.resolve("inside.jpg"), "inside");
        var candidates = new ArrayList<ResolvedFileCandidate>();
        var walker = new Version3DiscoveryWalker(path -> path.equals(child)
                ? ContinuityProbeResult.unavailable()
                : ContinuityProbeResult.accepted(ACCEPTED));
        assertEquals(TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE,
                walker.walk(root, snapshot(), candidates::add));
        assertEquals(0, candidates.size());
    }

    @Test
    void symbolicLinkCannotAuthorizeCompleteTraversal() throws Exception {
        Path root = Files.createDirectory(directory.resolve("source"));
        Path elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("file.jpg"), "outside");
        try {
            Files.createSymbolicLink(root.resolve("alias"), elsewhere);
        } catch (java.io.IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "Host does not permit symbolic links in this test directory");
        }
        var candidates = new ArrayList<ResolvedFileCandidate>();
        var walker = new Version3DiscoveryWalker(
                ignored -> ContinuityProbeResult.accepted(ACCEPTED));
        assertEquals(TraversalCompletion.Issue.SYMBOLIC_LINK_AMBIGUITY,
                walker.walk(root, snapshot(), candidates::add));
        assertEquals(0, candidates.size());
    }

    private static ScanAuthoritySnapshot snapshot() {
        var context = new MacOsApfsLocationContextEvidence(
                1, MacOsApfsLocationContextEvidence.PROFILE, 1,
                ANCHOR, LocationKeyCodec.encode(ANCHOR), "apfs", VOLUME_ID, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        var source = new MacOsApfsSourceRootEvidence(
                1, MacOsApfsSourceRootEvidence.PROFILE, 1, CONTEXT_ID, 3, 3,
                ANCHOR, LocationKeyCodec.encode(ANCHOR), VOLUME_ID, "10",
                new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        return new ScanAuthoritySnapshot(1, 3, CONTEXT_ID, 3,
                ANCHOR, ANCHOR, context, context, source, source);
    }

    private static LocationPath path(String text) {
        return LocationPathParser.parse(LocationDialect.UNIX, text);
    }
}
