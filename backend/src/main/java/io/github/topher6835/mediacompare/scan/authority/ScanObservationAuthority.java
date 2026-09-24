package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;
import java.util.Optional;

import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceAuthority;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.ContinuityVerificationResult;
import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityVerifier;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootComparisonContext;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;

/** Pure eligibility decisions over already captured durable rows and host observations. */
public final class ScanObservationAuthority {
    private static final MacOsApfsLocationContextEvidenceCodec LEGACY_CONTEXT_CODEC =
            new MacOsApfsLocationContextEvidenceCodec();

    private ScanObservationAuthority() {
    }

    public static ScanAuthorityResult<ScanAuthoritySnapshot> capture(
            Source source, LocationContext context,
            ContinuityProbeResult<MacOsApfsLocationContextEvidence> contextProbe,
            ContinuityProbeResult<MacOsApfsSourceRootEvidence> rootProbe) {
        Objects.requireNonNull(source, "Source");
        if (source.id() == null || source.id() <= 0 || source.locationRevision() < 0) {
            throw new IllegalArgumentException("Persisted Source ID and revision are required");
        }
        if (source.boundLocationContextId() == null) {
            if (source.rootPathDialect() != null || source.bindingEvidenceJson() != null) {
                throw new IllegalStateException("Unbound Source has contradictory binding fields");
            }
            return denied(ScanAuthorityOutcome.UNBOUND, ScanAuthorityReason.SOURCE_UNBOUND);
        }
        if (!LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())) {
            return denied(ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        }
        if (context == null) {
            return denied(ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        }
        if (!source.boundLocationContextId().equals(context.id())) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_ID_CHANGED);
        }
        if (context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_NOT_CURRENT);
        }

        // These helpers deliberately throw for malformed persisted envelopes or row/evidence disagreement.
        Optional<LocationContextAcceptanceEvidence> acceptance = requireCurrentAcceptance(context);
        if (acceptance.isEmpty()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_NOT_CURRENT);
        }
        var binding = SourceBindingAuthority.requireCurrentBound(source);
        MacOsApfsLocationContextEvidence contextBaseline =
                acceptance.orElseThrow().macOsApfsEvidence();
        MacOsApfsSourceRootEvidence rootBaseline = binding.macOsApfsSourceRootEvidence();
        if (rootBaseline.locationContextRevision() != context.revision()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_REVISION_CHANGED);
        }
        var comparison = new MacOsApfsSourceRootComparisonContext(
                context.id(), context.revision(), source.locationRevision(), contextBaseline);
        ContinuityVerificationResult baselineCheck =
                MacOsApfsContinuityVerifier.validateInitialSourceRoot(comparison, rootBaseline);
        if (baselineCheck.outcome() != ContinuityOutcome.ACCEPTED) {
            throw new IllegalStateException("Persisted Source-root baseline disagrees with accepted context");
        }
        if (contextProbe == null || rootProbe == null) {
            return denied(ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        }
        if (contextProbe.outcome() != ContinuityOutcome.ACCEPTED) {
            return fromProbe(contextProbe.outcome());
        }
        if (rootProbe.outcome() != ContinuityOutcome.ACCEPTED) {
            return fromProbe(rootProbe.outcome());
        }
        MacOsApfsLocationContextEvidence contextObserved = contextProbe.evidence().orElseThrow();
        MacOsApfsSourceRootEvidence rootObserved = rootProbe.evidence().orElseThrow();
        ContinuityVerificationResult contextCheck =
                MacOsApfsContinuityVerifier.verifyLocationContext(contextBaseline, contextObserved);
        if (contextCheck.outcome() != ContinuityOutcome.ACCEPTED) {
            return fromProbe(contextCheck.outcome());
        }
        ContinuityVerificationResult rootCheck =
                MacOsApfsContinuityVerifier.verifySourceRoot(comparison, rootBaseline, rootObserved);
        if (rootCheck.outcome() != ContinuityOutcome.ACCEPTED) {
            return fromProbe(rootCheck.outcome());
        }
        return ScanAuthorityResult.trusted(new ScanAuthoritySnapshot(
                source.id(), source.locationRevision(), context.id(), context.revision(),
                rootBaseline.rootLocationPath(), contextBaseline.anchorLocationPath(),
                contextBaseline, contextObserved, rootBaseline, rootObserved));
    }

    public static ScanAuthorityResult<ResolvedFileCandidate> resolve(
            ScanAuthoritySnapshot authority, ScanFileObservation observed) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(observed, "observed file");
        if (!LocationKeyCodec.matches(observed.fileLocationPath(), observed.fileLocationKey())) {
            throw new IllegalArgumentException("Observed exact path and canonical key disagree");
        }
        if (observed.sourceId() != authority.sourceId()
                || observed.sourceLocationRevision() != authority.sourceRevision()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_REVISION_CHANGED);
        }
        if (!authority.contextId().equals(observed.locationContextId())) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_ID_CHANGED);
        }
        if (observed.locationContextRevision() != authority.contextRevision()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_REVISION_CHANGED);
        }
        if (!authority.contextAnchor().contains(observed.fileLocationPath())) {
            return denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_OUTSIDE_CONTEXT);
        }
        if (!authority.sourceRoot().contains(observed.fileLocationPath())
                || authority.sourceRoot().equals(observed.fileLocationPath())) {
            return denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_OUTSIDE_SOURCE);
        }
        if (observed.symbolicLinkBoundary()) {
            return denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.SYMBOLIC_LINK_BOUNDARY);
        }
        if (!observed.regularFile()) {
            return denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_NOT_REGULAR);
        }
        switch (observed.childStorageBoundary()) {
            case UNSUPPORTED -> {
                return denied(ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.CHILD_STORAGE_UNSUPPORTED);
            }
            case UNCERTAIN -> {
                return denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.CHILD_STORAGE_UNCERTAIN);
            }
            case UNAVAILABLE -> {
                return denied(ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
            }
            case SAME_ACCEPTED_VOLUME -> {
                // Continue with independent provider and Volume UUID checks below.
            }
        }
        if (!MacOsApfsLocationContextEvidence.FILE_SYSTEM_TYPE.equals(observed.fileSystemType())) {
            return denied(ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        }
        if (!authority.contextBaseline().volumeUuid().equals(observed.volumeUuid())
                || !authority.rootBaseline().volumeUuid().equals(observed.volumeUuid())) {
            return denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.VOLUME_IDENTITY_MISMATCH);
        }
        String relativePath = SourceRelativePath.from(authority.sourceRoot(), observed.fileLocationPath());
        return ScanAuthorityResult.trusted(new ResolvedFileCandidate(
                authority.sourceId(), authority.sourceRevision(), authority.contextId(),
                authority.contextRevision(), observed.fileLocationPath(), observed.fileLocationKey(),
                relativePath, relativePath, observed.fileSystemType(), observed.volumeUuid(),
                observed.childStorageBoundary(), observed.regularFile(), observed.symbolicLinkBoundary(),
                observed.sizeBytes(), observed.modifiedTimeEpochSecond(), observed.modifiedTimeNano()));
    }

    public static ScanAuthorityResult<MissingClaimAuthority> assessTraversal(
            ScanAuthorityResult<ScanAuthoritySnapshot> start,
            ScanAuthorityResult<ScanAuthoritySnapshot> end,
            TraversalCompletion completion) {
        Objects.requireNonNull(start, "start authority");
        Objects.requireNonNull(end, "end authority");
        Objects.requireNonNull(completion, "traversal completion");
        if (start.outcome() != ScanAuthorityOutcome.TRUSTED) {
            return denied(start.outcome(), start.reason());
        }
        if (end.outcome() != ScanAuthorityOutcome.TRUSTED) {
            return denied(end.outcome(), end.reason());
        }
        ScanAuthoritySnapshot first = start.value().orElseThrow();
        ScanAuthoritySnapshot last = end.value().orElseThrow();
        if (!first.sourceRoot().contains(completion.startScope())) {
            throw new IllegalArgumentException("Requested traversal scope is outside Source root");
        }
        if (!completion.startScope().equals(completion.endScope())) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.TRAVERSAL_SCOPE_CHANGED);
        }
        if (first.sourceId() != last.sourceId()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_ID_CHANGED);
        }
        if (first.sourceRevision() != last.sourceRevision()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_REVISION_CHANGED);
        }
        if (!first.contextId().equals(last.contextId())) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_ID_CHANGED);
        }
        if (first.contextRevision() != last.contextRevision()) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_REVISION_CHANGED);
        }
        if (!first.sourceRoot().equals(last.sourceRoot())
                || !first.rootBaseline().equals(last.rootBaseline())
                || MacOsApfsContinuityVerifier.verifySourceRoot(new MacOsApfsSourceRootComparisonContext(
                        first.contextId(), first.contextRevision(), first.sourceRevision(),
                        first.contextBaseline()), first.rootObservation(), last.rootObservation()).outcome()
                        != ContinuityOutcome.ACCEPTED) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_ROOT_CHANGED);
        }
        if (!first.contextBaseline().equals(last.contextBaseline())
                || MacOsApfsContinuityVerifier.verifyLocationContext(
                        first.contextObservation(), last.contextObservation()).outcome()
                        != ContinuityOutcome.ACCEPTED) {
            return denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.AUTHORITY_CHANGED);
        }
        return switch (completion.issue()) {
            case COMPLETE -> ScanAuthorityResult.trusted(new MissingClaimAuthority(
                    first.sourceId(), first.sourceRevision(), first.contextId(),
                    first.contextRevision(), completion.startScope()));
            case ERROR -> denied(ScanAuthorityOutcome.INCOMPLETE, ScanAuthorityReason.TRAVERSAL_ERROR);
            case CANCELLED -> denied(ScanAuthorityOutcome.INCOMPLETE, ScanAuthorityReason.TRAVERSAL_CANCELLED);
            case INACCESSIBLE_SUBTREE -> denied(
                    ScanAuthorityOutcome.INCOMPLETE, ScanAuthorityReason.INACCESSIBLE_SUBTREE);
            case UNSUPPORTED_CHILD_STORAGE -> denied(
                    ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.CHILD_STORAGE_UNSUPPORTED);
            case UNCERTAIN_CHILD_STORAGE -> denied(
                    ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.CHILD_STORAGE_UNCERTAIN);
            case SYMBOLIC_LINK_AMBIGUITY -> denied(
                    ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.SYMBOLIC_LINK_BOUNDARY);
        };
    }

    private static <T> ScanAuthorityResult<T> fromProbe(ContinuityOutcome outcome) {
        return switch (outcome) {
            case UNAVAILABLE -> denied(ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
            case UNSUPPORTED -> denied(ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
            case UNCERTAIN, ERROR -> denied(ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.AUTHORITY_UNCERTAIN);
            case MISMATCH -> denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.AUTHORITY_CHANGED);
            case ACCEPTED -> throw new IllegalArgumentException("Accepted probe cannot be a failure");
        };
    }

    private static Optional<LocationContextAcceptanceEvidence> requireCurrentAcceptance(LocationContext context) {
        try {
            return Optional.of(LocationContextAcceptanceAuthority.requireCurrentAccepted(context));
        } catch (IllegalStateException integrityFailure) {
            if (isValidLegacyAcceptance(context)) {
                return Optional.empty();
            }
            throw integrityFailure;
        }
    }

    private static boolean isValidLegacyAcceptance(LocationContext context) {
        final MacOsApfsLocationContextEvidence legacyEvidence;
        try {
            legacyEvidence = LEGACY_CONTEXT_CODEC.decode(context.continuityEvidenceJson());
        } catch (IllegalArgumentException malformedOrNotLegacy) {
            return false;
        }

        final io.github.topher6835.mediacompare.location.LocationPath storedAnchor;
        try {
            storedAnchor = LocationAnchorPolicy.validateAnchor(
                    context.anchorLocationPath(), context.anchorLocationKey());
        } catch (IllegalArgumentException malformedStoredAnchor) {
            return false;
        }
        return legacyEvidence.anchorLocationPath().equals(storedAnchor)
                && legacyEvidence.anchorLocationKey().value().equals(context.anchorLocationKey())
                && legacyEvidence.directory()
                && !legacyEvidence.symbolicLink();
    }

    private static <T> ScanAuthorityResult<T> denied(ScanAuthorityOutcome outcome, ScanAuthorityReason reason) {
        return ScanAuthorityResult.denied(outcome, reason);
    }
}
