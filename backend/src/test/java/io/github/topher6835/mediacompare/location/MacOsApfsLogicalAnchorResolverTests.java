package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.junit.jupiter.api.Test;

import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector.MountObservation;

class MacOsApfsLogicalAnchorResolverTests {
    private static final String DATA = "11111111-1111-1111-1111-111111111111";
    private static final String SYSTEM = "22222222-2222-2222-2222-222222222222";
    private static final String ARCHIVE = "33333333-3333-3333-3333-333333333333";

    @Test
    void findsVisibleFirmlinkBoundaryByVolumeUuid() {
        FakeMounts mounts = new FakeMounts();
        mounts.put("/Users/chris/Pictures", DATA, "/System/Volumes/Data");
        mounts.put("/Users/chris", DATA, "/System/Volumes/Data");
        mounts.put("/Users", DATA, "/System/Volumes/Data");
        mounts.put("/", SYSTEM, "/");

        assertAnchor("/Users", resolver(mounts).resolve(unix("/Users/chris/Pictures")));
        assertEquals(2, mounts.calls("/"));
    }

    @Test
    void findsExternalVolumeBoundary() {
        FakeMounts mounts = archiveMounts();
        assertAnchor("/Volumes/Archive", resolver(mounts).resolve(unix("/Volumes/Archive/Photos")));
    }

    @Test
    void sourceAtBoundaryReturnsItself() {
        FakeMounts mounts = new FakeMounts();
        mounts.put("/Volumes/Archive", ARCHIVE, "/Volumes/Archive");
        mounts.put("/Volumes", SYSTEM, "/");
        assertAnchor("/Volumes/Archive", resolver(mounts).resolve(unix("/Volumes/Archive")));
    }

    @Test
    void rootSourceNeverProbesAboveRoot() {
        FakeMounts mounts = new FakeMounts();
        mounts.put("/", SYSTEM, "/");
        assertAnchor("/", resolver(mounts).resolve(unix("/")));
        assertEquals(2, mounts.calls("/"));
    }

    @Test
    void initialSourceFailuresRetainTheirOutcomeWithoutAnAnchor() {
        FakeMounts unsupported = new FakeMounts();
        unsupported.putResult("/Volumes/Archive", ContinuityProbeResult.unsupported());
        assertFailure(ContinuityOutcome.UNSUPPORTED,
                resolver(unsupported).resolve(unix("/Volumes/Archive")));

        FakeMounts unavailable = new FakeMounts();
        unavailable.putResult("/Volumes/Archive", ContinuityProbeResult.unavailable());
        assertFailure(ContinuityOutcome.UNAVAILABLE,
                resolver(unavailable).resolve(unix("/Volumes/Archive")));
    }

    @Test
    void unavailableUncertainAndErrorParentFailClosed() {
        for (ContinuityProbeResult<MountObservation> failure : List.of(
                ContinuityProbeResult.<MountObservation>unavailable(),
                ContinuityProbeResult.<MountObservation>uncertain(ContinuityReason.PROBE_UNCERTAIN),
                ContinuityProbeResult.<MountObservation>error())) {
            FakeMounts mounts = new FakeMounts();
            mounts.put("/Volumes/Archive", ARCHIVE, "/Volumes/Archive");
            mounts.putResult("/Volumes", failure);
            assertFailure(failure.outcome(), resolver(mounts).resolve(unix("/Volumes/Archive")));
        }
    }

    @Test
    void physicalMountTextDoesNotDecideVolumeContinuity() {
        FakeMounts mounts = new FakeMounts();
        mounts.put("/Volumes/Archive/Photos", ARCHIVE, "/same-text");
        mounts.put("/Volumes/Archive", ARCHIVE, "/same-text");
        mounts.put("/Volumes", SYSTEM, "/same-text");
        assertAnchor("/Volumes/Archive", resolver(mounts).resolve(unix("/Volumes/Archive/Photos")));
    }

    @Test
    void sameUuidWithDifferentPhysicalMountIsUncertain() {
        FakeMounts mounts = new FakeMounts();
        mounts.put("/Volumes/Archive/Photos", ARCHIVE, "/Volumes/Archive");
        mounts.put("/Volumes/Archive", ARCHIVE, "/other-mount");
        assertFailure(ContinuityOutcome.UNCERTAIN,
                resolver(mounts).resolve(unix("/Volumes/Archive/Photos")));
    }

    @Test
    void changedSourceVolumeDuringRecheckIsUncertain() {
        FakeMounts mounts = archiveMounts();
        mounts.setSequence("/Volumes/Archive/Photos",
                ContinuityProbeResult.accepted(observation(ARCHIVE, "/Volumes/Archive")),
                ContinuityProbeResult.accepted(observation(SYSTEM, "/")));
        assertFailure(ContinuityOutcome.UNCERTAIN,
                resolver(mounts).resolve(unix("/Volumes/Archive/Photos")));
    }

    @Test
    void changedBoundaryOrPhysicalMountDuringRecheckIsUncertain() {
        FakeMounts mounts = archiveMounts();
        mounts.setSequence("/Volumes",
                ContinuityProbeResult.accepted(observation(SYSTEM, "/")),
                ContinuityProbeResult.accepted(observation(ARCHIVE, "/")));
        assertFailure(ContinuityOutcome.UNCERTAIN,
                resolver(mounts).resolve(unix("/Volumes/Archive/Photos")));
    }

    @Test
    void changedExactSpellingIsUncertain() {
        FakeMounts mounts = archiveMounts();
        int[] resolutions = { 0 };
        MacOsApfsLogicalAnchorResolver resolver = new MacOsApfsLogicalAnchorResolver(
                requested -> ContinuityProbeResult.accepted(
                        ++resolutions[0] == 1 ? requested : unix("/Volumes/ARCHIVE/Photos")),
                path -> Path.of(toUnixString(path)), mounts::inspect);
        assertFailure(ContinuityOutcome.UNCERTAIN, resolver.resolve(unix("/Volumes/Archive/Photos")));
    }

    private static FakeMounts archiveMounts() {
        FakeMounts mounts = new FakeMounts();
        mounts.put("/Volumes/Archive/Photos", ARCHIVE, "/Volumes/Archive");
        mounts.put("/Volumes/Archive", ARCHIVE, "/Volumes/Archive");
        mounts.put("/Volumes", SYSTEM, "/");
        return mounts;
    }

    private static MacOsApfsLogicalAnchorResolver resolver(FakeMounts mounts) {
        return new MacOsApfsLogicalAnchorResolver(
                ContinuityProbeResult::accepted,
                path -> Path.of(toUnixString(path)), mounts::inspect);
    }

    private static LocationPath unix(String path) {
        return LocationPathParser.parse(LocationDialect.UNIX, path);
    }

    private static String toUnixString(LocationPath path) {
        return path.components().isEmpty() ? "/" : "/" + String.join("/", path.components());
    }

    private static MountObservation observation(String volume, String mountPoint) {
        return new MountObservation("/dev/disk1s1", mountPoint, "apfs", volume);
    }

    private static void assertAnchor(String expected, ContinuityProbeResult<LocationPath> result) {
        assertEquals(ContinuityOutcome.ACCEPTED, result.outcome());
        assertEquals(unix(expected), result.evidence().orElseThrow());
    }

    private static void assertFailure(ContinuityOutcome expected, ContinuityProbeResult<LocationPath> result) {
        assertEquals(expected, result.outcome());
        assertTrue(result.evidence().isEmpty());
    }

    private static final class FakeMounts {
        private final Map<String, Queue<ContinuityProbeResult<MountObservation>>> results = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();

        void put(String path, String volume, String mountPoint) {
            putResult(path, ContinuityProbeResult.accepted(observation(volume, mountPoint)));
        }

        void putResult(String path, ContinuityProbeResult<MountObservation> result) {
            results.computeIfAbsent(path, ignored -> new ArrayDeque<>()).add(result);
        }

        @SafeVarargs
        final void setSequence(String path, ContinuityProbeResult<MountObservation>... observations) {
            results.put(path, new ArrayDeque<>(List.of(observations)));
        }

        ContinuityProbeResult<MountObservation> inspect(Path path) {
            String key = path.toString();
            calls.merge(key, 1, Integer::sum);
            Queue<ContinuityProbeResult<MountObservation>> sequence = results.get(key);
            if (sequence == null || sequence.isEmpty()) {
                throw new AssertionError("Unexpected mount observation: " + key);
            }
            return sequence.size() == 1 ? sequence.element() : sequence.remove();
        }

        int calls(String path) {
            return calls.getOrDefault(path, 0);
        }
    }
}
