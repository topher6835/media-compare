package io.github.topher6835.mediacompare.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class MacOsApfsContinuityVerifierTests {

    @Test
    void acceptsMatchingContextWhileIgnoringTimestampAndDiagnostics() {
        MacOsApfsLocationContextEvidence baseline = EvidenceTestFixtures.context();
        MacOsApfsLocationContextEvidence observation = EvidenceTestFixtures.context(
                EvidenceTestFixtures.ANCHOR,
                EvidenceTestFixtures.VOLUME_UUID,
                "2", true, false, 1,
                new MacOsApfsLocationContextEvidence.Diagnostics("9", "other", "provider"));

        assertAccepted(MacOsApfsContinuityVerifier.verifyLocationContext(baseline, observation));
    }

    @Test
    void rejectsChangedContextAnchorVolumeInodeAndClassificationWithStableReasons() {
        MacOsApfsLocationContextEvidence baseline = EvidenceTestFixtures.context();

        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.ANCHOR_LOCATION_MISMATCH,
                MacOsApfsContinuityVerifier.verifyLocationContext(baseline,
                        EvidenceTestFixtures.context(unix("/Volumes/Other"),
                                EvidenceTestFixtures.VOLUME_UUID, "2", true, false, 0,
                                MacOsApfsLocationContextEvidence.Diagnostics.empty())));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_VOLUME_UUID_MISMATCH,
                MacOsApfsContinuityVerifier.verifyLocationContext(baseline,
                        EvidenceTestFixtures.context(EvidenceTestFixtures.ANCHOR,
                                "22222222-2222-2222-2222-222222222222", "2", true, false, 0,
                                MacOsApfsLocationContextEvidence.Diagnostics.empty())));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_INODE_MISMATCH,
                MacOsApfsContinuityVerifier.verifyLocationContext(baseline,
                        EvidenceTestFixtures.context(EvidenceTestFixtures.ANCHOR,
                                EvidenceTestFixtures.VOLUME_UUID, "3", true, false, 0,
                                MacOsApfsLocationContextEvidence.Diagnostics.empty())));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.ANCHOR_DIRECTORY_MISMATCH,
                MacOsApfsContinuityVerifier.verifyLocationContext(baseline,
                        EvidenceTestFixtures.context(EvidenceTestFixtures.ANCHOR,
                                EvidenceTestFixtures.VOLUME_UUID, "2", false, false, 0,
                                MacOsApfsLocationContextEvidence.Diagnostics.empty())));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.ANCHOR_SYMBOLIC_LINK_MISMATCH,
                MacOsApfsContinuityVerifier.verifyLocationContext(baseline,
                        EvidenceTestFixtures.context(EvidenceTestFixtures.ANCHOR,
                                EvidenceTestFixtures.VOLUME_UUID, "2", true, true, 0,
                                MacOsApfsLocationContextEvidence.Diagnostics.empty())));
    }

    @Test
    void acceptsMatchingContainedSourceRootWhileIgnoringTimestamp() {
        MacOsApfsSourceRootEvidence baseline = EvidenceTestFixtures.sourceRoot();
        MacOsApfsSourceRootEvidence observation = EvidenceTestFixtures.sourceRoot(
                EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                baseline.rootBirthTime(), true, false, 1);

        assertAccepted(MacOsApfsContinuityVerifier.verifySourceRoot(
                EvidenceTestFixtures.comparisonContext(), baseline, observation));
    }

    @Test
    void rejectsSourceContextAndRevisionContradictions() {
        MacOsApfsSourceRootEvidence baseline = EvidenceTestFixtures.sourceRoot();

        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_CONTEXT_ID_MISMATCH,
                MacOsApfsContinuityVerifier.verifySourceRoot(
                        new MacOsApfsSourceRootComparisonContext(
                                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 3, 7,
                                EvidenceTestFixtures.context()), baseline, baseline));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_REVISION_MISMATCH,
                MacOsApfsContinuityVerifier.verifySourceRoot(
                        new MacOsApfsSourceRootComparisonContext(
                                EvidenceTestFixtures.CONTEXT_ID, 4, 7,
                                EvidenceTestFixtures.context()), baseline, baseline));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_LOCATION_REVISION_MISMATCH,
                MacOsApfsContinuityVerifier.verifySourceRoot(
                        new MacOsApfsSourceRootComparisonContext(
                                EvidenceTestFixtures.CONTEXT_ID, 3, 8,
                                EvidenceTestFixtures.context()), baseline, baseline));

        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_CONTEXT_ID_MISMATCH,
                compare(baseline, source("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 3, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        baseline.rootBirthTime(), true, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_REVISION_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 4, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        baseline.rootBirthTime(), true, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_LOCATION_REVISION_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 8,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        baseline.rootBirthTime(), true, false)));
    }

    @Test
    void distinguishesSourceRootIdentityAndStructuralContainment() {
        MacOsApfsSourceRootEvidence baseline = EvidenceTestFixtures.sourceRoot();
        LocationPath otherInside = unix("/Volumes/Archive/Documents");

        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_LOCATION_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        otherInside, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        baseline.rootBirthTime(), true, false)));

        LocationPath narrowAnchor = unix("/Volumes/A");
        LocationPath outside = unix("/Volumes/AB");
        MacOsApfsSourceRootEvidence outsideBaseline = source(
                EvidenceTestFixtures.CONTEXT_ID, 3, 7, outside,
                EvidenceTestFixtures.VOLUME_UUID, "123456", baseline.rootBirthTime(), true, false);
        assertResult(ContinuityOutcome.UNCERTAIN, ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT,
                MacOsApfsContinuityVerifier.verifySourceRoot(
                        new MacOsApfsSourceRootComparisonContext(
                                EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                                EvidenceTestFixtures.context(
                                        narrowAnchor, EvidenceTestFixtures.VOLUME_UUID, "2", true, false, 0,
                                        MacOsApfsLocationContextEvidence.Diagnostics.empty())),
                        outsideBaseline, outsideBaseline));

        LocationPath inside = unix("/Volumes/Archive/Photos/2024");
        MacOsApfsSourceRootEvidence insideBaseline = source(
                EvidenceTestFixtures.CONTEXT_ID, 3, 7, inside,
                EvidenceTestFixtures.VOLUME_UUID, "123456", baseline.rootBirthTime(), true, false);
        assertAccepted(compare(insideBaseline, insideBaseline));
    }

    @Test
    void rejectsSourceVolumeInodeBirthTimeAndClassificationChanges() {
        MacOsApfsSourceRootEvidence baseline = EvidenceTestFixtures.sourceRoot();

        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        EvidenceTestFixtures.ROOT, "22222222-2222-2222-2222-222222222222", "123456",
                        baseline.rootBirthTime(), true, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_INODE_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123457",
                        baseline.rootBirthTime(), true, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_BIRTH_TIME_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        new MacOsApfsSourceRootEvidence.BirthTime(
                                baseline.rootBirthTime().epochSecond() + 1,
                                baseline.rootBirthTime().nano()), true, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_BIRTH_TIME_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        new MacOsApfsSourceRootEvidence.BirthTime(
                                baseline.rootBirthTime().epochSecond(),
                                baseline.rootBirthTime().nano() + 1), true, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_DIRECTORY_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        baseline.rootBirthTime(), false, false)));
        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_SYMBOLIC_LINK_MISMATCH,
                compare(baseline, source(EvidenceTestFixtures.CONTEXT_ID, 3, 7,
                        EvidenceTestFixtures.ROOT, EvidenceTestFixtures.VOLUME_UUID, "123456",
                        baseline.rootBirthTime(), true, true)));
    }

    @Test
    void rejectsBaselineWhoseVolumeDoesNotMatchItsContext() {
        MacOsApfsSourceRootEvidence contradictory = source(
                EvidenceTestFixtures.CONTEXT_ID, 3, 7, EvidenceTestFixtures.ROOT,
                "22222222-2222-2222-2222-222222222222", "123456",
                EvidenceTestFixtures.sourceRoot().rootBirthTime(), true, false);

        assertResult(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH,
                compare(contradictory, contradictory));
    }

    @Test
    void resultModelAcceptsOnlyMappedOutcomeReasons() {
        for (OutcomeReason expected : List.of(
                new OutcomeReason(ContinuityOutcome.ACCEPTED, ContinuityReason.EVIDENCE_MATCHED),
                new OutcomeReason(ContinuityOutcome.UNAVAILABLE, ContinuityReason.PROBE_UNAVAILABLE),
                new OutcomeReason(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROBE_UNCERTAIN),
                new OutcomeReason(ContinuityOutcome.UNCERTAIN, ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT),
                new OutcomeReason(ContinuityOutcome.UNSUPPORTED, ContinuityReason.PROFILE_UNSUPPORTED),
                new OutcomeReason(ContinuityOutcome.ERROR, ContinuityReason.PROBE_ERROR),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.ANCHOR_LOCATION_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_VOLUME_UUID_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_INODE_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.ANCHOR_DIRECTORY_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.ANCHOR_SYMBOLIC_LINK_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_CONTEXT_ID_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.CONTEXT_REVISION_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_LOCATION_REVISION_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_LOCATION_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_INODE_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_BIRTH_TIME_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_DIRECTORY_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.SOURCE_ROOT_SYMBOLIC_LINK_MISMATCH))) {
            ContinuityVerificationResult actual = new ContinuityVerificationResult(
                    expected.outcome(), expected.reason());
            assertResult(expected.outcome(), expected.reason(), actual);
        }
    }

    @Test
    void resultModelRejectsCrossCategoryOutcomeReasons() {
        for (OutcomeReason invalid : List.of(
                new OutcomeReason(ContinuityOutcome.ACCEPTED, ContinuityReason.CONTEXT_INODE_MISMATCH),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.EVIDENCE_MATCHED),
                new OutcomeReason(ContinuityOutcome.UNAVAILABLE, ContinuityReason.CONTEXT_INODE_MISMATCH),
                new OutcomeReason(ContinuityOutcome.ERROR, ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT),
                new OutcomeReason(ContinuityOutcome.MISMATCH, ContinuityReason.PROBE_ERROR),
                new OutcomeReason(ContinuityOutcome.UNCERTAIN, ContinuityReason.PROFILE_UNSUPPORTED),
                new OutcomeReason(ContinuityOutcome.UNSUPPORTED, ContinuityReason.SOURCE_ROOT_INODE_MISMATCH))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ContinuityVerificationResult(invalid.outcome(), invalid.reason()));
        }
    }

    private static ContinuityVerificationResult compare(
            MacOsApfsSourceRootEvidence baseline,
            MacOsApfsSourceRootEvidence observation) {
        return MacOsApfsContinuityVerifier.verifySourceRoot(
                EvidenceTestFixtures.comparisonContext(), baseline, observation);
    }

    private static MacOsApfsSourceRootEvidence source(
            String contextId,
            long contextRevision,
            long sourceRevision,
            LocationPath root,
            String volumeUuid,
            String inode,
            MacOsApfsSourceRootEvidence.BirthTime birthTime,
            boolean directory,
            boolean symbolicLink) {
        return EvidenceTestFixtures.sourceRoot(
                contextId, contextRevision, sourceRevision, root, volumeUuid, inode,
                birthTime, directory, symbolicLink, 0);
    }

    private static LocationPath unix(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }

    private static void assertAccepted(ContinuityVerificationResult result) {
        assertResult(ContinuityOutcome.ACCEPTED, ContinuityReason.EVIDENCE_MATCHED, result);
    }

    private static void assertResult(
            ContinuityOutcome outcome,
            ContinuityReason reason,
            ContinuityVerificationResult result) {
        assertEquals(outcome, result.outcome());
        assertEquals(reason, result.reason());
    }

    private record OutcomeReason(ContinuityOutcome outcome, ContinuityReason reason) {
    }
}
