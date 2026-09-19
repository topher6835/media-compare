package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Pure comparison only: no probing, persistence, baseline refresh, or state transition. */
public final class MacOsApfsContinuityVerifier {

    private MacOsApfsContinuityVerifier() {
    }

    public static ContinuityVerificationResult verifyLocationContext(
            MacOsApfsLocationContextEvidence baseline,
            MacOsApfsLocationContextEvidence observation) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(observation, "observation");

        if (!compatible(baseline, observation)) {
            return ContinuityVerificationResult.unsupported(ContinuityReason.PROFILE_UNSUPPORTED);
        }
        if (!baseline.anchorLocationPath().equals(observation.anchorLocationPath())
                || !baseline.anchorLocationKey().equals(observation.anchorLocationKey())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.ANCHOR_LOCATION_MISMATCH);
        }
        if (!baseline.volumeUuid().equals(observation.volumeUuid())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.CONTEXT_VOLUME_UUID_MISMATCH);
        }
        if (!baseline.anchorInode().equals(observation.anchorInode())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.CONTEXT_INODE_MISMATCH);
        }
        if (!observation.directory() || baseline.directory() != observation.directory()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.ANCHOR_DIRECTORY_MISMATCH);
        }
        if (observation.symbolicLink() || baseline.symbolicLink() != observation.symbolicLink()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.ANCHOR_SYMBOLIC_LINK_MISMATCH);
        }
        return ContinuityVerificationResult.accepted();
    }

    public static ContinuityVerificationResult verifySourceRoot(
            MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence baseline,
            MacOsApfsSourceRootEvidence observation) {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(observation, "observation");

        if (!compatible(baseline, observation)) {
            return ContinuityVerificationResult.unsupported(ContinuityReason.PROFILE_UNSUPPORTED);
        }
        if (!comparison.locationContextId().equals(baseline.locationContextId())
                || !baseline.locationContextId().equals(observation.locationContextId())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_CONTEXT_ID_MISMATCH);
        }
        if (comparison.locationContextRevision() != baseline.locationContextRevision()
                || baseline.locationContextRevision() != observation.locationContextRevision()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.CONTEXT_REVISION_MISMATCH);
        }
        if (comparison.sourceLocationRevision() != baseline.sourceLocationRevision()
                || baseline.sourceLocationRevision() != observation.sourceLocationRevision()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_LOCATION_REVISION_MISMATCH);
        }
        if (!baseline.rootLocationPath().equals(observation.rootLocationPath())
                || !baseline.rootLocationKey().equals(observation.rootLocationKey())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_LOCATION_MISMATCH);
        }

        LocationPath anchor = comparison.locationContextEvidence().anchorLocationPath();
        if (!anchor.contains(baseline.rootLocationPath()) || !anchor.contains(observation.rootLocationPath())) {
            return ContinuityVerificationResult.uncertain(ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT);
        }
        String contextVolumeUuid = comparison.locationContextEvidence().volumeUuid();
        if (!contextVolumeUuid.equals(baseline.volumeUuid())
                || !baseline.volumeUuid().equals(observation.volumeUuid())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH);
        }
        if (!baseline.rootInode().equals(observation.rootInode())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_INODE_MISMATCH);
        }
        if (!baseline.rootBirthTime().equals(observation.rootBirthTime())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_BIRTH_TIME_MISMATCH);
        }
        if (!observation.directory() || baseline.directory() != observation.directory()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_DIRECTORY_MISMATCH);
        }
        if (observation.symbolicLink() || baseline.symbolicLink() != observation.symbolicLink()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_SYMBOLIC_LINK_MISMATCH);
        }
        return ContinuityVerificationResult.accepted();
    }

    private static boolean compatible(
            MacOsApfsLocationContextEvidence first,
            MacOsApfsLocationContextEvidence second) {
        return first.version() == second.version()
                && first.profile().equals(second.profile())
                && first.profileVersion() == second.profileVersion()
                && first.fileSystemType().equals(second.fileSystemType());
    }

    private static boolean compatible(
            MacOsApfsSourceRootEvidence first,
            MacOsApfsSourceRootEvidence second) {
        return first.version() == second.version()
                && first.profile().equals(second.profile())
                && first.profileVersion() == second.profileVersion();
    }
}
