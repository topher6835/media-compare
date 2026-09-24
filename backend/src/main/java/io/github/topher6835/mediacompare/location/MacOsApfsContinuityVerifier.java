package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Pure evidence validation and comparison: no probing, persistence, or state transition. */
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
        if (!matchesContextId(comparison, baseline) || !matchesContextId(comparison, observation)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_CONTEXT_ID_MISMATCH);
        }
        if (!matchesContextRevision(comparison, baseline)
                || !matchesContextRevision(comparison, observation)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.CONTEXT_REVISION_MISMATCH);
        }
        if (!matchesSourceRevision(comparison, baseline)
                || !matchesSourceRevision(comparison, observation)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_LOCATION_REVISION_MISMATCH);
        }
        if (!baseline.rootLocationPath().equals(observation.rootLocationPath())
                || !baseline.rootLocationKey().equals(observation.rootLocationKey())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_LOCATION_MISMATCH);
        }
        if (!withinContext(comparison, baseline) || !withinContext(comparison, observation)) {
            return ContinuityVerificationResult.uncertain(ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT);
        }
        if (!matchesContextVolume(comparison, baseline) || !matchesContextVolume(comparison, observation)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH);
        }
        if (!baseline.rootInode().equals(observation.rootInode())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_INODE_MISMATCH);
        }
        if (!baseline.rootBirthTime().equals(observation.rootBirthTime())) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_BIRTH_TIME_MISMATCH);
        }
        if (!baseline.directory() || !observation.directory()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_DIRECTORY_MISMATCH);
        }
        if (baseline.symbolicLink() || observation.symbolicLink()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_SYMBOLIC_LINK_MISMATCH);
        }
        return ContinuityVerificationResult.accepted();
    }

    /** Validates one accepted probe candidate before it becomes the first Source-root baseline. */
    public static ContinuityVerificationResult validateInitialSourceRoot(
            MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence candidate) {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(candidate, "candidate");
        // The typed records enforce profile, canonical path/key, inode, birth time, and timestamp.
        if (!matchesContextId(comparison, candidate)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_CONTEXT_ID_MISMATCH);
        }
        if (!matchesContextRevision(comparison, candidate)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.CONTEXT_REVISION_MISMATCH);
        }
        if (!matchesSourceRevision(comparison, candidate)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_LOCATION_REVISION_MISMATCH);
        }
        if (!withinContext(comparison, candidate)) {
            return ContinuityVerificationResult.uncertain(ContinuityReason.SOURCE_ROOT_OUTSIDE_CONTEXT);
        }
        if (!matchesContextVolume(comparison, candidate)) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_VOLUME_UUID_MISMATCH);
        }
        if (!candidate.directory()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_DIRECTORY_MISMATCH);
        }
        if (candidate.symbolicLink()) {
            return ContinuityVerificationResult.mismatch(ContinuityReason.SOURCE_ROOT_SYMBOLIC_LINK_MISMATCH);
        }
        return ContinuityVerificationResult.accepted();
    }

    private static boolean matchesContextId(MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence candidate) {
        return comparison.locationContextId().equals(candidate.locationContextId());
    }

    private static boolean matchesContextRevision(MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence candidate) {
        return comparison.locationContextRevision() == candidate.locationContextRevision();
    }

    private static boolean matchesSourceRevision(MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence candidate) {
        return comparison.sourceLocationRevision() == candidate.sourceLocationRevision();
    }

    private static boolean withinContext(MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence candidate) {
        return comparison.locationContextEvidence().anchorLocationPath().contains(candidate.rootLocationPath());
    }

    private static boolean matchesContextVolume(MacOsApfsSourceRootComparisonContext comparison,
            MacOsApfsSourceRootEvidence candidate) {
        return comparison.locationContextEvidence().volumeUuid().equals(candidate.volumeUuid());
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
