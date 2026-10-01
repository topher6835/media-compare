package io.github.topher6835.mediacompare.scan.authority;

import io.github.topher6835.mediacompare.catalog.CurrentLocationAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.location.LocationDialect;

/** Pure NTFS eligibility and continuity decisions; native observations arrive from the host adapter. */
public final class WindowsNtfsScanAuthority {
    private static final WindowsNtfsEvidenceCodec CODEC = new WindowsNtfsEvidenceCodec();

    private WindowsNtfsScanAuthority() { }

    public static ScanAuthorityResult<ScanAuthoritySnapshot> capture(Source source, LocationContext context,
            WindowsNtfsIdentity contextObserved, WindowsNtfsIdentity rootObserved) {
        if (source.boundLocationContextId() == null) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.UNBOUND, ScanAuthorityReason.SOURCE_UNBOUND);
        }
        if (!LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        }
        if (context == null) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        }
        if (!source.boundLocationContextId().equals(context.id())) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_ID_CHANGED);
        }
        if (context.lifecycleStatus() != LocationContext.LifecycleStatus.ACTIVE
                || context.continuityStatus() != LocationContext.ContinuityStatus.ACCEPTED) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.CONTEXT_NOT_CURRENT);
        }
        var route = CurrentLocationAuthority.requirePersisted(source, context);
        var baselineContext = CODEC.decodeContext(context.continuityEvidenceJson());
        var baselineRoot = CODEC.decodeSource(source.bindingEvidenceJson());
        if (!route.root().equals(baselineRoot.root()) || !route.anchor().equals(baselineContext.anchor())) {
            throw new IllegalStateException("NTFS authority route disagrees with persisted evidence");
        }
        if (contextObserved == null || rootObserved == null) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.UNAVAILABLE, ScanAuthorityReason.AUTHORITY_UNAVAILABLE);
        }
        if (!baselineContext.identity().equals(contextObserved)
                || !baselineRoot.identity().equals(rootObserved)
                || !contextObserved.volumeSerial().equals(rootObserved.volumeSerial())) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.STALE, ScanAuthorityReason.AUTHORITY_CHANGED);
        }
        return ScanAuthorityResult.trusted(new ScanAuthoritySnapshot(source.id(), source.locationRevision(),
                context.id(), context.revision(), baselineContext, contextObserved, baselineRoot, rootObserved));
    }
}
