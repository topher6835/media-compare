package io.github.topher6835.mediacompare.scan.authority;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;

class ScanObservationAuthorityTests {
    private static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_CONTEXT_ID = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
    private static final String VOLUME_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_VOLUME_UUID = "22222222-3333-4444-5555-666666666666";
    private static final LocationPath ANCHOR = path("/Volumes/Archive");
    private static final LocationPath PHOTOS = path("/Volumes/Archive/Photos");
    private static final LocationPath FILE = path("/Volumes/Archive/Photos/a.jpg");

    @Test
    void trustedDirectAndDeepFilesUseExactStructuredLocation() {
        Fixture fixture = fixture(101, 7, 3, CONTEXT_ID, PHOTOS);
        ScanAuthoritySnapshot authority = trusted(fixture);
        ResolvedFileCandidate direct = trusted(ScanObservationAuthority.resolve(authority, file(FILE)));
        assertEquals("a.jpg", direct.relativePath());
        assertEquals(direct.relativePath(), direct.relativePathKey());
        assertEquals(LocationKeyCodec.encode(FILE), direct.fileLocationKey());
        assertEquals(VOLUME_UUID, direct.volumeUuid());
        assertEquals(ChildStorageBoundary.SAME_ACCEPTED_VOLUME, direct.childStorageBoundary());
        assertTrue(direct.regularFile());
        assertFalse(direct.symbolicLinkBoundary());
        assertEquals(12, direct.sizeBytes());
        assertEquals(456, direct.modifiedTimeNano());

        LocationPath deep = path("/Volumes/Archive/Photos/Nested/Ä/a.JPG");
        ResolvedFileCandidate nested = trusted(ScanObservationAuthority.resolve(authority, file(deep)));
        assertEquals("Nested/Ä/a.JPG", nested.relativePath());
        assertEquals(deep, nested.fileLocationPath());
        assertEquals("Nested/Ä/a.JPG", SourceRelativePath.from(PHOTOS, deep));
    }

    @Test
    void overlappingSourcesConvergeOnAbsoluteIdentityButKeepOwnRelativePaths() {
        ResolvedFileCandidate parent = trusted(ScanObservationAuthority.resolve(
                trusted(fixture(101, 7, 3, CONTEXT_ID, ANCHOR)), file(FILE, 101, 7, CONTEXT_ID, 3)));
        ResolvedFileCandidate child = trusted(ScanObservationAuthority.resolve(
                trusted(fixture(102, 7, 3, CONTEXT_ID, PHOTOS)), file(FILE, 102, 7, CONTEXT_ID, 3)));
        assertEquals(parent.fileLocationPath(), child.fileLocationPath());
        assertEquals(parent.fileLocationKey(), child.fileLocationKey());
        assertEquals(parent.locationContextId(), child.locationContextId());
        assertNotEquals(parent.sourceId(), child.sourceId());
        assertEquals("Photos/a.jpg", parent.relativePath());
        assertEquals("a.jpg", child.relativePath());
        for (var component : ResolvedFileCandidate.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("hash"));
            assertFalse(component.getName().toLowerCase().contains("content"));
        }
    }

    @Test
    void wrongPathKeyAndInvalidRelativeRelationshipsFail() {
        ScanAuthoritySnapshot authority = trusted(fixture(101, 7, 3, CONTEXT_ID, PHOTOS));
        ScanFileObservation wrongKey = file(FILE, LocationKeyCodec.encode(path("/Volumes/Archive/Photos/b.jpg")),
                101, 7, CONTEXT_ID, 3, "apfs", VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false);
        assertThrows(IllegalArgumentException.class, () -> ScanObservationAuthority.resolve(authority, wrongKey));
        assertThrows(IllegalArgumentException.class, () -> SourceRelativePath.from(PHOTOS, PHOTOS));
        assertThrows(IllegalArgumentException.class, () -> SourceRelativePath.from(PHOTOS,
                path("/Volumes/Archive/Other/a.jpg")));
        assertThrows(IllegalArgumentException.class, () -> SourceRelativePath.from(PHOTOS,
                LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, "C:\\Photos\\a.jpg")));
    }

    @Test
    void outsideContextSourceAndSiblingAreRefused() {
        ScanAuthoritySnapshot authority = trusted(fixture(101, 7, 3, CONTEXT_ID, PHOTOS));
        assertDenied(ScanObservationAuthority.resolve(authority, file(path("/Other/a.jpg"))),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_OUTSIDE_CONTEXT);
        assertDenied(ScanObservationAuthority.resolve(authority,
                file(path("/Volumes/Archive/Other/a.jpg"))),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_OUTSIDE_SOURCE);
        assertDenied(ScanObservationAuthority.resolve(authority,
                file(path("/Volumes/Archive/Photos2/a.jpg"))),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_OUTSIDE_SOURCE);
        assertDenied(ScanObservationAuthority.resolve(authority, file(PHOTOS)),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_OUTSIDE_SOURCE);
    }

    @Test
    void capturedIdsAndRevisionsMustMatchCurrentAuthority() {
        ScanAuthoritySnapshot authority = trusted(fixture(101, 7, 3, CONTEXT_ID, PHOTOS));
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, 101, 8, CONTEXT_ID, 3)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_REVISION_CHANGED);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, 101, 7, CONTEXT_ID, 4)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_REVISION_CHANGED);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, 101, 7, OTHER_CONTEXT_ID, 3)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_ID_CHANGED);
    }

    @Test
    void providerBoundaryVolumeLinkAndTypeMustBeTrusted() {
        ScanAuthoritySnapshot authority = trusted(fixture(101, 7, 3, CONTEXT_ID, PHOTOS));
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "ext4", VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false)),
                ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "apfs", OTHER_VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false)),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.VOLUME_IDENTITY_MISMATCH);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "apfs", VOLUME_UUID,
                ChildStorageBoundary.UNSUPPORTED, true, false)),
                ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.CHILD_STORAGE_UNSUPPORTED);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "apfs", VOLUME_UUID,
                ChildStorageBoundary.UNCERTAIN, true, false)),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.CHILD_STORAGE_UNCERTAIN);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "apfs", VOLUME_UUID,
                ChildStorageBoundary.UNAVAILABLE, true, false)),
                ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "apfs", VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, true)),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.SYMBOLIC_LINK_BOUNDARY);
        assertDenied(ScanObservationAuthority.resolve(authority, file(FILE, LocationKeyCodec.encode(FILE),
                101, 7, CONTEXT_ID, 3, "apfs", VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, false, false)),
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.FILE_NOT_REGULAR);
    }

    @Test
    void unboundUnsupportedAndUnavailableAuthorityNeverResolve() {
        Fixture fixture = fixture(101, 7, 3, CONTEXT_ID, PHOTOS);
        Source unbound = new Source(101L, "Legacy", "/Volumes/Archive/Photos",
                "/Volumes/Archive/Photos", 7, 1, 2);
        assertDenied(ScanObservationAuthority.capture(unbound, fixture.context(),
                contextProbe(fixture), rootProbe(fixture)),
                ScanAuthorityOutcome.UNBOUND, ScanAuthorityReason.SOURCE_UNBOUND);
        assertDenied(ScanObservationAuthority.capture(unbound, null, null, null),
                ScanAuthorityOutcome.UNBOUND, ScanAuthorityReason.SOURCE_UNBOUND);
        Source windows = new Source(101L, "Windows", "C:\\Photos", "legacy", 7,
                "win-drive", CONTEXT_ID, fixture.source().bindingEvidenceJson(), 1, 2);
        assertDenied(ScanObservationAuthority.capture(windows, fixture.context(),
                contextProbe(fixture), rootProbe(fixture)),
                ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        assertDenied(ScanObservationAuthority.capture(windows, null, null, null),
                ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        assertDenied(ScanObservationAuthority.capture(fixture.source(), null,
                contextProbe(fixture), rootProbe(fixture)),
                ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        assertDenied(ScanObservationAuthority.capture(fixture.source(), fixture.context(),
                ContinuityProbeResult.unavailable(), rootProbe(fixture)),
                ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        assertDenied(ScanObservationAuthority.capture(fixture.source(), fixture.context(), null, null),
                ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        LocationContext review = new LocationContext(CONTEXT_ID, fixture.context().anchorLocationPath(),
                fixture.context().anchorLocationKey(), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.REVIEW_REQUIRED, 3, null, 1, 2);
        assertDenied(ScanObservationAuthority.capture(fixture.source(), review,
                contextProbe(fixture), rootProbe(fixture)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_NOT_CURRENT);
    }

    @Test
    void malformedDurableEvidenceFailsIntegrityAndStaleCaptureIsRefused() {
        Fixture fixture = fixture(101, 7, 3, CONTEXT_ID, PHOTOS);
        LocationContext raw = new LocationContext(CONTEXT_ID, fixture.context().anchorLocationPath(),
                fixture.context().anchorLocationKey(), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.ACCEPTED, 3, "{}", 1, 2);
        assertThrows(IllegalStateException.class, () -> ScanObservationAuthority.capture(
                fixture.source(), raw, contextProbe(fixture), rootProbe(fixture)));
        Source staleBinding = new Source(101L, "Stale", fixture.source().rootPath(),
                fixture.source().rootPathKey(), 8, "unix", CONTEXT_ID,
                fixture.source().bindingEvidenceJson(), 1, 2);
        assertThrows(IllegalStateException.class, () -> ScanObservationAuthority.capture(
                staleBinding, fixture.context(), contextProbe(fixture), rootProbe(fixture)));
        assertDenied(ScanObservationAuthority.capture(fixture.source(), fixture.context(),
                contextProbe(fixture), ContinuityProbeResult.mismatch(
                        io.github.topher6835.mediacompare.location.ContinuityReason.SOURCE_ROOT_INODE_MISMATCH)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.AUTHORITY_CHANGED);
    }

    @Test
    void validLegacyRawAcceptanceIsHistoricalButNotCurrentScanAuthority() {
        Fixture fixture = fixture(101, 7, 3, CONTEXT_ID, PHOTOS);
        String legacyRawEvidence = new MacOsApfsLocationContextEvidenceCodec()
                .encode(fixture.contextEvidence());
        LocationContext legacyAccepted = new LocationContext(
                CONTEXT_ID, fixture.context().anchorLocationPath(), fixture.context().anchorLocationKey(),
                LocationContext.LifecycleStatus.ACTIVE, LocationContext.ContinuityStatus.ACCEPTED,
                3, legacyRawEvidence, 1, 2);

        assertDenied(ScanObservationAuthority.capture(fixture.source(), legacyAccepted,
                contextProbe(fixture), rootProbe(fixture)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_NOT_CURRENT);
    }

    @Test
    void completeStableTraversalAuthorizesOnlyItsOwnScope() {
        Fixture fixture = fixture(101, 7, 3, CONTEXT_ID, PHOTOS);
        ScanAuthorityResult<ScanAuthoritySnapshot> start = capture(fixture);
        ScanAuthorityResult<ScanAuthoritySnapshot> end = capture(fixture);
        MissingClaimAuthority authority = trusted(ScanObservationAuthority.assessTraversal(
                start, end, complete(PHOTOS)));
        assertEquals(101, authority.sourceId());
        assertEquals(PHOTOS, authority.scope());
        assertDenied(ScanObservationAuthority.assessTraversal(start,
                capture(fixture(102, 7, 3, CONTEXT_ID, PHOTOS)), complete(PHOTOS)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_ID_CHANGED);
    }

    @Test
    void revisionsRootAndScopeChangesPreventMissingClaims() {
        ScanAuthorityResult<ScanAuthoritySnapshot> start = capture(fixture(101, 7, 3, CONTEXT_ID, PHOTOS));
        assertDenied(ScanObservationAuthority.assessTraversal(start,
                capture(fixture(101, 8, 3, CONTEXT_ID, PHOTOS)), complete(PHOTOS)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_REVISION_CHANGED);
        assertDenied(ScanObservationAuthority.assessTraversal(start,
                capture(fixture(101, 7, 4, CONTEXT_ID, PHOTOS)), complete(PHOTOS)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_REVISION_CHANGED);
        assertDenied(ScanObservationAuthority.assessTraversal(start,
                capture(fixture(101, 7, 3, OTHER_CONTEXT_ID, PHOTOS)), complete(PHOTOS)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_ID_CHANGED);
        assertDenied(ScanObservationAuthority.assessTraversal(start,
                capture(fixture(101, 7, 3, CONTEXT_ID, path("/Volumes/Archive/Other"))),
                complete(PHOTOS)), ScanAuthorityOutcome.STALE, ScanAuthorityReason.SOURCE_ROOT_CHANGED);
        assertDenied(ScanObservationAuthority.assessTraversal(start, start,
                new TraversalCompletion(PHOTOS, path("/Volumes/Archive/Photos/Nested"),
                        TraversalCompletion.Issue.COMPLETE)),
                ScanAuthorityOutcome.STALE, ScanAuthorityReason.TRAVERSAL_SCOPE_CHANGED);
        assertThrows(IllegalArgumentException.class, () -> ScanObservationAuthority.assessTraversal(
                start, start, complete(path("/Other"))));
    }

    @Test
    void everyIncompleteTraversalReasonDeniesMissingWithoutInvalidatingPositiveCandidate() {
        Fixture fixture = fixture(101, 7, 3, CONTEXT_ID, PHOTOS);
        ScanAuthorityResult<ScanAuthoritySnapshot> start = capture(fixture);
        ResolvedFileCandidate positive = trusted(ScanObservationAuthority.resolve(trusted(start), file(FILE)));
        assertEquals(FILE, positive.fileLocationPath());
        assertTraversalDenied(start, TraversalCompletion.Issue.ERROR,
                ScanAuthorityOutcome.INCOMPLETE, ScanAuthorityReason.TRAVERSAL_ERROR);
        assertTraversalDenied(start, TraversalCompletion.Issue.CANCELLED,
                ScanAuthorityOutcome.INCOMPLETE, ScanAuthorityReason.TRAVERSAL_CANCELLED);
        assertTraversalDenied(start, TraversalCompletion.Issue.INACCESSIBLE_SUBTREE,
                ScanAuthorityOutcome.INCOMPLETE, ScanAuthorityReason.INACCESSIBLE_SUBTREE);
        assertTraversalDenied(start, TraversalCompletion.Issue.UNSUPPORTED_CHILD_STORAGE,
                ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.CHILD_STORAGE_UNSUPPORTED);
        assertTraversalDenied(start, TraversalCompletion.Issue.UNCERTAIN_CHILD_STORAGE,
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.CHILD_STORAGE_UNCERTAIN);
        assertTraversalDenied(start, TraversalCompletion.Issue.SYMBOLIC_LINK_AMBIGUITY,
                ScanAuthorityOutcome.UNCERTAIN, ScanAuthorityReason.SYMBOLIC_LINK_BOUNDARY);
    }

    private static void assertTraversalDenied(ScanAuthorityResult<ScanAuthoritySnapshot> snapshot,
            TraversalCompletion.Issue issue, ScanAuthorityOutcome outcome, ScanAuthorityReason reason) {
        assertDenied(ScanObservationAuthority.assessTraversal(snapshot, snapshot,
                new TraversalCompletion(PHOTOS, PHOTOS, issue)), outcome, reason);
    }

    private static TraversalCompletion complete(LocationPath scope) {
        return new TraversalCompletion(scope, scope, TraversalCompletion.Issue.COMPLETE);
    }

    private static ScanAuthorityResult<ScanAuthoritySnapshot> capture(Fixture fixture) {
        return ScanObservationAuthority.capture(fixture.source(), fixture.context(),
                contextProbe(fixture), rootProbe(fixture));
    }

    private static ScanAuthoritySnapshot trusted(Fixture fixture) {
        return trusted(capture(fixture));
    }

    private static <T> T trusted(ScanAuthorityResult<T> result) {
        assertEquals(ScanAuthorityOutcome.TRUSTED, result.outcome(), () -> result.reason().name());
        return result.value().orElseThrow();
    }

    private static void assertDenied(ScanAuthorityResult<?> result,
            ScanAuthorityOutcome outcome, ScanAuthorityReason reason) {
        assertEquals(outcome, result.outcome());
        assertEquals(reason, result.reason());
        assertTrue(result.value().isEmpty());
    }

    private static ScanFileObservation file(LocationPath path) {
        return file(path, 101, 7, CONTEXT_ID, 3);
    }

    private static ScanFileObservation file(LocationPath path, long sourceId, long sourceRevision,
            String contextId, long contextRevision) {
        return file(path, LocationKeyCodec.encode(path), sourceId, sourceRevision, contextId,
                contextRevision, "apfs", VOLUME_UUID,
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false);
    }

    private static ScanFileObservation file(LocationPath path, LocationKey key, long sourceId,
            long sourceRevision, String contextId, long contextRevision, String fileSystemType,
            String volumeUuid, ChildStorageBoundary boundary, boolean regular, boolean symbolic) {
        return new ScanFileObservation(sourceId, sourceRevision, contextId, contextRevision,
                path, key, fileSystemType, volumeUuid, boundary, regular, symbolic, 12, 123, 456);
    }

    private static Fixture fixture(long sourceId, long sourceRevision, long contextRevision,
            String contextId, LocationPath root) {
        MacOsApfsLocationContextEvidence contextEvidence = contextEvidence(1);
        MacOsApfsSourceRootEvidence rootEvidence = rootEvidence(root, contextId,
                contextRevision, sourceRevision, 1);
        LocationContextAcceptanceEvidence acceptance = new LocationContextAcceptanceEvidence(
                1, contextId, contextRevision, contextEvidence);
        LocationContext context = new LocationContext(contextId, new LocationPathCodec().encode(ANCHOR),
                LocationKeyCodec.encode(ANCHOR).value(), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.ACCEPTED, contextRevision,
                new LocationContextAcceptanceEvidenceCodec().encode(acceptance), 1, 2);
        SourceBindingEvidence binding = new SourceBindingEvidence(1, sourceId, rootEvidence);
        Source source = new Source(sourceId, "Source", unix(root), LocationKeyCodec.encode(root).value(),
                sourceRevision, "unix", contextId,
                new SourceBindingEvidenceCodec().encode(binding), 1, 2);
        return new Fixture(source, context, contextEvidence, rootEvidence);
    }

    private static ContinuityProbeResult<MacOsApfsLocationContextEvidence> contextProbe(Fixture fixture) {
        return ContinuityProbeResult.accepted(contextEvidence(2));
    }

    private static ContinuityProbeResult<MacOsApfsSourceRootEvidence> rootProbe(Fixture fixture) {
        MacOsApfsSourceRootEvidence baseline = fixture.rootEvidence();
        return ContinuityProbeResult.accepted(rootEvidence(baseline.rootLocationPath(),
                baseline.locationContextId(), baseline.locationContextRevision(),
                baseline.sourceLocationRevision(), 2));
    }

    private static MacOsApfsLocationContextEvidence contextEvidence(long observedAt) {
        return new MacOsApfsLocationContextEvidence(1, MacOsApfsLocationContextEvidence.PROFILE, 1,
                ANCHOR, LocationKeyCodec.encode(ANCHOR), "apfs", VOLUME_UUID, "2",
                true, false, observedAt, MacOsApfsLocationContextEvidence.Diagnostics.empty());
    }

    private static MacOsApfsSourceRootEvidence rootEvidence(LocationPath root, String contextId,
            long contextRevision, long sourceRevision, long observedAt) {
        return new MacOsApfsSourceRootEvidence(1, MacOsApfsSourceRootEvidence.PROFILE, 1,
                contextId, contextRevision, sourceRevision, root, LocationKeyCodec.encode(root),
                VOLUME_UUID, "10", new MacOsApfsSourceRootEvidence.BirthTime(100, 200),
                true, false, observedAt);
    }

    private static String unix(LocationPath path) {
        return "/" + String.join("/", path.components());
    }

    private static LocationPath path(String value) {
        return LocationPathParser.parse(LocationDialect.UNIX, value);
    }

    private record Fixture(Source source, LocationContext context,
            MacOsApfsLocationContextEvidence contextEvidence,
            MacOsApfsSourceRootEvidence rootEvidence) {
    }
}
