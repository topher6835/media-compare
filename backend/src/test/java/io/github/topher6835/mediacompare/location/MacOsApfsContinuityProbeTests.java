package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.topher6835.mediacompare.location.MacOsDiskutilPlistParser.DiskutilInfo;

class MacOsApfsContinuityProbeTests {

    private static final Clock CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_900_000_000_123L), ZoneOffset.UTC);
    private static final Path HOST_PATH = Path.of("/Volumes/Archive/Photos");
    private static final DiskutilInfo APFS = new DiskutilInfo(
            "apfs", EvidenceTestFixtures.VOLUME_UUID);

    @Test
    void capturesStableContextUsingExactResolvedPathKeyAndClock() {
        LocationPath requested = unix("/volumes/archive");
        LocationPath exact = EvidenceTestFixtures.ANCHOR;
        MacOsApfsContinuityProbe probe = probe(
                requestedPath -> ContinuityProbeResult.accepted(exact),
                new SequenceReader(contextObservation("2"), contextObservation("2")),
                path -> ContinuityProbeResult.accepted(APFS));

        ContinuityProbeResult<MacOsApfsLocationContextEvidence> result =
                probe.captureLocationContext(requested);
        MacOsApfsLocationContextEvidence evidence = result.evidence().orElseThrow();

        assertEquals(ContinuityOutcome.ACCEPTED, result.outcome());
        assertEquals(exact, evidence.anchorLocationPath());
        assertEquals(LocationKeyCodec.encode(exact), evidence.anchorLocationKey());
        assertEquals(EvidenceTestFixtures.VOLUME_UUID, evidence.volumeUuid());
        assertEquals("2", evidence.anchorInode());
        assertEquals(CLOCK.millis(), evidence.acceptedAtMs());
        assertEquals("16777234", evidence.diagnostics().unixDevice());
    }

    @Test
    void captureBracketsBothProviderObservationsWithFilesystemEvidence() {
        List<String> contextCalls = new ArrayList<>();
        Queue<MacOsApfsContinuityProbe.FileObservation> contextObservations = new ArrayDeque<>(List.of(
                contextObservation("2"), contextObservation("2")));
        MacOsApfsContinuityProbe contextProbe = new MacOsApfsContinuityProbe(
                requested -> {
                    contextCalls.add("resolve");
                    return ContinuityProbeResult.accepted(EvidenceTestFixtures.ANCHOR);
                },
                path -> HOST_PATH,
                path -> {
                    contextCalls.add("filesystem");
                    return contextObservations.remove();
                },
                path -> {
                    contextCalls.add("provider");
                    return ContinuityProbeResult.accepted(APFS);
                },
                CLOCK,
                () -> true);

        assertEquals(ContinuityOutcome.ACCEPTED,
                contextProbe.captureLocationContext(EvidenceTestFixtures.ANCHOR).outcome());
        assertEquals(List.of("resolve", "filesystem", "provider", "provider", "filesystem", "resolve"),
                contextCalls);

        List<String> sourceCalls = new ArrayList<>();
        Queue<MacOsApfsContinuityProbe.FileObservation> sourceObservations = new ArrayDeque<>(List.of(
                sourceObservation("123456", birth(10, 20)),
                sourceObservation("123456", birth(10, 20))));
        MacOsApfsContinuityProbe sourceProbe = new MacOsApfsContinuityProbe(
                requested -> {
                    sourceCalls.add("resolve");
                    return ContinuityProbeResult.accepted(EvidenceTestFixtures.ROOT);
                },
                path -> HOST_PATH,
                path -> {
                    sourceCalls.add("filesystem");
                    return sourceObservations.remove();
                },
                path -> {
                    sourceCalls.add("provider");
                    return ContinuityProbeResult.accepted(APFS);
                },
                CLOCK,
                () -> true);

        assertEquals(ContinuityOutcome.ACCEPTED,
                sourceProbe.captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)).outcome());
        assertEquals(List.of("resolve", "filesystem", "provider", "provider", "filesystem", "resolve"),
                sourceCalls);
    }

    @Test
    void contextRejectsInodeAndClassificationInstabilityWithoutEvidence() {
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedAnchor(),
                        new SequenceReader(contextObservation("2"), contextObservation("3")),
                        acceptedVolume()).captureLocationContext(EvidenceTestFixtures.ANCHOR));

        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedAnchor(),
                        new SequenceReader(contextObservation("2"),
                                observation("2", birth(10, 20), false, false)),
                        acceptedVolume()).captureLocationContext(EvidenceTestFixtures.ANCHOR));
    }

    @Test
    void contextRequiresStableVolumeUuidAcrossTheCaptureWindow() {
        DiskutilInfo replacement = new DiskutilInfo(
                "apfs", "22222222-2222-2222-2222-222222222222");

        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedAnchor(),
                        new SequenceReader(contextObservation("2"), contextObservation("2")),
                        new SequenceVolumeInspector(APFS, replacement))
                        .captureLocationContext(EvidenceTestFixtures.ANCHOR));

        assertEquals(ContinuityOutcome.ACCEPTED,
                probe(acceptedAnchor(),
                        new SequenceReader(contextObservation("2"), contextObservation("2")),
                        new SequenceVolumeInspector(APFS, APFS))
                        .captureLocationContext(EvidenceTestFixtures.ANCHOR).outcome());
    }

    @Test
    void contextRejectsSymlinkUnavailableUnsupportedAndProviderErrorWithoutEvidence() {
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedAnchor(),
                        new SequenceReader(observation("2", birth(10, 20), true, true)),
                        acceptedVolume()).captureLocationContext(EvidenceTestFixtures.ANCHOR));

        assertFailure(ContinuityOutcome.UNAVAILABLE, ContinuityReason.PROBE_UNAVAILABLE,
                probe(acceptedAnchor(), path -> {
                    throw new NoSuchFileException(path.toString());
                }, acceptedVolume()).captureLocationContext(EvidenceTestFixtures.ANCHOR));

        assertFailure(ContinuityOutcome.UNSUPPORTED, ContinuityReason.PROFILE_UNSUPPORTED,
                probe(acceptedAnchor(), new SequenceReader(contextObservation("2")),
                        path -> ContinuityProbeResult.unsupported())
                        .captureLocationContext(EvidenceTestFixtures.ANCHOR));

        assertFailure(ContinuityOutcome.ERROR, ContinuityReason.PROBE_ERROR,
                probe(acceptedAnchor(), new SequenceReader(contextObservation("2")),
                        path -> ContinuityProbeResult.error())
                        .captureLocationContext(EvidenceTestFixtures.ANCHOR));
    }

    @Test
    void capturesStableSourceWithSuppliedRevisionsAndFullBirthTime() {
        LocationPath requested = unix("/volumes/archive/photos");
        MacOsApfsSourceRootEvidence.BirthTime birth = birth(1_799_999_900L, 123_456_789);
        MacOsApfsContinuityProbe probe = probe(
                requestedPath -> ContinuityProbeResult.accepted(EvidenceTestFixtures.ROOT),
                new SequenceReader(
                        observation("123456", birth, true, false),
                        observation("123456", birth, true, false)),
                acceptedVolume());

        ContinuityProbeResult<MacOsApfsSourceRootEvidence> result = probe.captureSourceRoot(
                sourceRequest(requested));
        MacOsApfsSourceRootEvidence evidence = result.evidence().orElseThrow();

        assertEquals(ContinuityOutcome.ACCEPTED, result.outcome());
        assertEquals(EvidenceTestFixtures.CONTEXT_ID, evidence.locationContextId());
        assertEquals(3, evidence.locationContextRevision());
        assertEquals(7, evidence.sourceLocationRevision());
        assertEquals(EvidenceTestFixtures.ROOT, evidence.rootLocationPath());
        assertEquals(LocationKeyCodec.encode(EvidenceTestFixtures.ROOT), evidence.rootLocationKey());
        assertEquals("123456", evidence.rootInode());
        assertEquals(birth, evidence.rootBirthTime());
        assertEquals(CLOCK.millis(), evidence.acceptedAtMs());
    }

    @Test
    void sourceRejectsStructuralOutsideAndVolumeMismatch() {
        MacOsApfsSourceRootProbeRequest outside = sourceRequest(unix("/Volumes/AB"));
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT,
                probe(path -> ContinuityProbeResult.accepted(path),
                        new SequenceReader(), acceptedVolume()).captureSourceRoot(outside));

        DiskutilInfo otherVolume = new DiskutilInfo(
                "apfs", "22222222-2222-2222-2222-222222222222");
        assertFailure(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH,
                probe(acceptedRoot(),
                        new SequenceReader(
                                sourceObservation("123456", birth(10, 20)),
                                sourceObservation("123456", birth(10, 20))),
                        path -> ContinuityProbeResult.accepted(otherVolume))
                        .captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));
    }

    @Test
    void sourceRejectsInodeAndBirthTimeInstability() {
        MacOsApfsSourceRootEvidence.BirthTime birth = birth(10, 20);
        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedRoot(),
                        new SequenceReader(
                                sourceObservation("123456", birth),
                                sourceObservation("123457", birth)),
                        acceptedVolume()).captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));

        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedRoot(),
                        new SequenceReader(
                                sourceObservation("123456", birth),
                                sourceObservation("123456", birth(10, 21))),
                        acceptedVolume()).captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));
    }

    @Test
    void sourceRequiresStableProviderVolumeAcrossTheCaptureWindow() {
        DiskutilInfo replacement = new DiskutilInfo(
                "apfs", "22222222-2222-2222-2222-222222222222");

        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedRoot(),
                        new SequenceReader(
                                sourceObservation("123456", birth(10, 20)),
                                sourceObservation("123456", birth(10, 20))),
                        new SequenceVolumeInspector(APFS, replacement))
                        .captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));
    }

    @Test
    void sourceRejectsUnavailableBirthTimeAndNeverReturnsFailureEvidence() {
        MacOsApfsContinuityProbe.FileObservation withoutBirth = new MacOsApfsContinuityProbe.FileObservation(
                "123456", Optional.empty(), true, false,
                "16777234", "/dev/disk9s1", "provider");

        assertFailure(ContinuityOutcome.UNAVAILABLE, ContinuityReason.PROBE_UNAVAILABLE,
                probe(acceptedRoot(), new SequenceReader(withoutBirth), acceptedVolume())
                        .captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));
    }

    @Test
    void sourceRejectsUnavailablePathSymlinkAndNonDirectory() {
        assertFailure(ContinuityOutcome.UNAVAILABLE, ContinuityReason.PROBE_UNAVAILABLE,
                probe(acceptedRoot(), path -> {
                    throw new NoSuchFileException(path.toString());
                }, acceptedVolume()).captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));

        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedRoot(),
                        new SequenceReader(observation("123456", birth(10, 20), true, true)),
                        acceptedVolume()).captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));

        assertFailure(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN,
                probe(acceptedRoot(),
                        new SequenceReader(observation("123456", birth(10, 20), false, false)),
                        acceptedVolume()).captureSourceRoot(sourceRequest(EvidenceTestFixtures.ROOT)));
    }

    @Test
    void platformGuardFailsClosedWithoutInvokingDependencies() {
        MacOsApfsContinuityProbe probe = new MacOsApfsContinuityProbe(
                path -> {
                    throw new AssertionError("resolver should not run");
                }, path -> HOST_PATH, path -> {
                    throw new AssertionError("filesystem should not run");
                }, path -> {
                    throw new AssertionError("diskutil should not run");
                }, CLOCK, () -> false);

        assertFailure(ContinuityOutcome.UNSUPPORTED, ContinuityReason.PROFILE_UNSUPPORTED,
                probe.captureLocationContext(EvidenceTestFixtures.ANCHOR));
    }

    @Test
    void probeResultEnforcesEvidenceInvariant() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContinuityProbeResult<>(
                        ContinuityOutcome.ACCEPTED,
                        ContinuityReason.EVIDENCE_MATCHED,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ContinuityProbeResult<>(
                        ContinuityOutcome.ERROR,
                        ContinuityReason.PROBE_ERROR,
                        Optional.of("untrusted")));
    }

    private static MacOsApfsContinuityProbe probe(
            Function<LocationPath, ContinuityProbeResult<LocationPath>> resolver,
            MacOsApfsContinuityProbe.FileEvidenceReader reader,
            MacOsApfsContinuityProbe.VolumeInspector volume) {
        return new MacOsApfsContinuityProbe(
                resolver, path -> HOST_PATH, reader, volume, CLOCK, () -> true);
    }

    private static Function<LocationPath, ContinuityProbeResult<LocationPath>> acceptedAnchor() {
        return path -> ContinuityProbeResult.accepted(EvidenceTestFixtures.ANCHOR);
    }

    private static Function<LocationPath, ContinuityProbeResult<LocationPath>> acceptedRoot() {
        return path -> ContinuityProbeResult.accepted(EvidenceTestFixtures.ROOT);
    }

    private static MacOsApfsContinuityProbe.VolumeInspector acceptedVolume() {
        return path -> ContinuityProbeResult.accepted(APFS);
    }

    private static MacOsApfsSourceRootProbeRequest sourceRequest(LocationPath root) {
        return new MacOsApfsSourceRootProbeRequest(
                EvidenceTestFixtures.CONTEXT_ID,
                3,
                7,
                EvidenceTestFixtures.context(),
                root);
    }

    private static MacOsApfsContinuityProbe.FileObservation contextObservation(String inode) {
        return observation(inode, birth(10, 20), true, false);
    }

    private static MacOsApfsContinuityProbe.FileObservation sourceObservation(
            String inode, MacOsApfsSourceRootEvidence.BirthTime birth) {
        return observation(inode, birth, true, false);
    }

    private static MacOsApfsContinuityProbe.FileObservation observation(
            String inode,
            MacOsApfsSourceRootEvidence.BirthTime birth,
            boolean directory,
            boolean symbolicLink) {
        return new MacOsApfsContinuityProbe.FileObservation(
                inode, Optional.of(birth), directory, symbolicLink,
                "16777234", "/dev/disk9s1", "provider");
    }

    private static MacOsApfsSourceRootEvidence.BirthTime birth(long seconds, int nanos) {
        return new MacOsApfsSourceRootEvidence.BirthTime(seconds, nanos);
    }

    private static LocationPath unix(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }

    private static void assertFailure(
            ContinuityOutcome outcome,
            ContinuityReason reason,
            ContinuityProbeResult<?> result) {
        assertEquals(outcome, result.outcome());
        assertEquals(reason, result.reason());
        assertTrue(result.evidence().isEmpty());
    }

    private static final class SequenceReader implements MacOsApfsContinuityProbe.FileEvidenceReader {
        private final Queue<MacOsApfsContinuityProbe.FileObservation> observations = new ArrayDeque<>();

        private SequenceReader(MacOsApfsContinuityProbe.FileObservation... observations) {
            for (MacOsApfsContinuityProbe.FileObservation observation : observations) {
                this.observations.add(observation);
            }
        }

        @Override
        public MacOsApfsContinuityProbe.FileObservation observe(Path path) throws IOException {
            MacOsApfsContinuityProbe.FileObservation observation = observations.poll();
            if (observation == null) {
                throw new AssertionError("unexpected filesystem observation");
            }
            return observation;
        }
    }

    private static final class SequenceVolumeInspector
            implements MacOsApfsContinuityProbe.VolumeInspector {
        private final Queue<DiskutilInfo> observations = new ArrayDeque<>();

        private SequenceVolumeInspector(DiskutilInfo... observations) {
            for (DiskutilInfo observation : observations) {
                this.observations.add(observation);
            }
        }

        @Override
        public ContinuityProbeResult<DiskutilInfo> inspect(Path path) {
            DiskutilInfo observation = observations.poll();
            if (observation == null) {
                throw new AssertionError("unexpected provider observation");
            }
            return ContinuityProbeResult.accepted(observation);
        }
    }
}
