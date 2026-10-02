package io.github.topher6835.mediacompare.scan.authority;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.github.topher6835.mediacompare.catalog.CurrentLocationAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourcePreparationState;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsContextEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsSourceEvidence;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.scan.Version3AuthorityCapture;

class WindowsNtfsScanAuthorityTests {
    private static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final WindowsNtfsIdentity ANCHOR_ID = identity("0000000000000001");
    private static final WindowsNtfsIdentity ROOT_ID = identity("0000000000000002");
    private static final LocationPath ANCHOR = path("D:\\");
    private static final LocationPath ROOT = path("D:\\Photos");
    private static final WindowsNtfsEvidenceCodec CODEC = new WindowsNtfsEvidenceCodec();

    @Test
    void identityAndEvidenceAreCanonicalAndRoundTrip() {
        assertEquals(ANCHOR_ID, identity("0000000000000001"));
        assertNotEquals(ANCHOR_ID, ROOT_ID);
        assertThrows(IllegalArgumentException.class,
                () -> new WindowsNtfsIdentity("1", "00000000000000000000000000000001"));
        assertThrows(IllegalArgumentException.class,
                () -> new WindowsNtfsIdentity("0000000000000001", "00000000000000000000000000000000"));
        var context = new WindowsNtfsContextEvidence(CONTEXT_ID, 3, ANCHOR, ANCHOR_ID);
        var source = new WindowsNtfsSourceEvidence(4, 7, CONTEXT_ID, 3, ROOT, ROOT_ID);
        assertEquals(context, CODEC.decodeContext(CODEC.encode(context)));
        assertEquals(source, CODEC.decodeSource(CODEC.encode(source)));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeContext(CODEC.encode(source)));
        assertThrows(IllegalArgumentException.class,
                () -> CODEC.decodeSource(CODEC.encode(source).replace("D", "E")));
    }

    @Test
    void persistedAuthorityAndScanRequireMatchingIdsAndRevisions() {
        var fixture = fixture();
        assertEquals(ROOT, CurrentLocationAuthority.requirePersisted(fixture.source(), fixture.context()).root());
        assertEquals(SourcePreparationState.READY, SourcePreparationState.from(fixture.source()));
        var eligible = WindowsNtfsScanAuthority.capture(fixture.source(), fixture.context(), null, null);
        assertEquals(ScanAuthorityOutcome.UNAVAILABLE, eligible.outcome());
        assertEquals(ScanAuthorityReason.AUTHORITY_UNAVAILABLE, eligible.reason());
        var trusted = WindowsNtfsScanAuthority.capture(fixture.source(), fixture.context(), ANCHOR_ID, ROOT_ID);
        assertEquals(ScanAuthorityOutcome.TRUSTED, trusted.outcome());
        assertEquals(ScanAuthorityOutcome.STALE,
                WindowsNtfsScanAuthority.capture(fixture.source(), fixture.context(), ANCHOR_ID,
                        identity("0000000000000003")).outcome());
        var wrongRevision = new Source(fixture.source().id(), fixture.source().name(),
                fixture.source().rootPath(), fixture.source().rootPathKey(), 8,
                fixture.source().rootPathDialect(), fixture.source().boundLocationContextId(),
                fixture.source().bindingEvidenceJson(), 1, 2);
        assertThrows(IllegalStateException.class,
                () -> CurrentLocationAuthority.requirePersisted(wrongRevision, fixture.context()));
    }

    @Test
    void resolvedWindowsFilesAndTraversalUseTheAcceptedVolume() {
        var fixture = fixture();
        var snapshot = WindowsNtfsScanAuthority.capture(fixture.source(), fixture.context(),
                ANCHOR_ID, ROOT_ID).value().orElseThrow();
        LocationPath file = path("D:\\Photos\\A.jpg");
        var observation = new ScanFileObservation(4, 7, CONTEXT_ID, 3, file,
                LocationKeyCodec.encode(file), "ntfs", ANCHOR_ID.volumeSerial(),
                ChildStorageBoundary.SAME_ACCEPTED_VOLUME, true, false, 5, 123, 456);
        var resolved = ScanObservationAuthority.resolve(snapshot, observation);
        assertEquals(ScanAuthorityOutcome.TRUSTED, resolved.outcome());
        assertEquals("A.jpg", resolved.value().orElseThrow().relativePath());
        var claim = ScanObservationAuthority.assessTraversal(
                ScanAuthorityResult.trusted(snapshot), ScanAuthorityResult.trusted(snapshot),
                new TraversalCompletion(ROOT, ROOT, TraversalCompletion.Issue.COMPLETE));
        assertEquals(ScanAuthorityOutcome.TRUSTED, claim.outcome());
        var changedRoot = new ScanAuthoritySnapshot(4, 7, CONTEXT_ID, 3,
                CODEC.decodeContext(fixture.context().continuityEvidenceJson()), ANCHOR_ID,
                CODEC.decodeSource(fixture.source().bindingEvidenceJson()),
                identity("0000000000000003"));
        assertFalse(snapshot.sameRootAs(changedRoot));
    }

    @Test
    void foreignHostCannotUsePortableWindowsAuthority() {
        if (HostFileSystems.isWindows()) return;
        var fixture = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> CurrentLocationAuthority.requireCurrentHost(fixture.source(), fixture.context()));
        var capture = new Version3AuthorityCapture(null, null);
        assertEquals(ScanAuthorityOutcome.UNSUPPORTED,
                capture.capture(fixture.source(), fixture.context()).outcome());
    }

    private static Fixture fixture() {
        var context = new LocationContext(CONTEXT_ID, new LocationPathCodec().encode(ANCHOR),
                LocationKeyCodec.encode(ANCHOR).value(), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.ACCEPTED, 3,
                CODEC.encode(new WindowsNtfsContextEvidence(CONTEXT_ID, 3, ANCHOR, ANCHOR_ID)), 1, 2);
        var source = new Source(4L, "Photos", "D:\\Photos", LocationKeyCodec.encode(ROOT).value(),
                7, "win-drive", CONTEXT_ID,
                CODEC.encode(new WindowsNtfsSourceEvidence(4, 7, CONTEXT_ID, 3, ROOT, ROOT_ID)), 1, 2);
        return new Fixture(source, context);
    }

    private static WindowsNtfsIdentity identity(String fileId) {
        return new WindowsNtfsIdentity("000000000000abcd", "0000000000000000" + fileId);
    }

    private static LocationPath path(String text) {
        return LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, text);
    }

    private record Fixture(Source source, LocationContext context) { }
}
