package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityReason;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityProbe;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootProbeRequest;
import io.github.topher6835.mediacompare.filesystem.HostFileStatus;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsPathInspector;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityResult;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthoritySnapshot;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityOutcome;
import io.github.topher6835.mediacompare.scan.authority.WindowsNtfsScanAuthority;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Captures fresh context and Source-root evidence outside catalog transactions. */
@Component
public class Version3AuthorityCapture {
    private final CatalogRepository catalog;
    private final LocationContextRepository contexts;
    private final MacOsApfsContinuityProbe probe;

    @Autowired
    public Version3AuthorityCapture(CatalogRepository catalog, LocationContextRepository contexts) {
        this(catalog, contexts, new MacOsApfsContinuityProbe());
    }

    Version3AuthorityCapture(CatalogRepository catalog, LocationContextRepository contexts,
            MacOsApfsContinuityProbe probe) {
        this.catalog = catalog;
        this.contexts = contexts;
        this.probe = probe;
    }

    public ScanAuthorityResult<ScanAuthoritySnapshot> capture(long sourceId) {
        var source = catalog.findSourceById(sourceId).orElseThrow();
        var context = source.boundLocationContextId() == null ? null
                : contexts.findById(source.boundLocationContextId()).orElse(null);
        return capture(source, context);
    }

    public ScanAuthorityResult<ScanAuthoritySnapshot> capture(Source source, LocationContext context) {
        if (LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())) {
            if (!HostFileSystems.current().supportsProfile(FileSystemProfile.NTFS)) {
                return ScanAuthorityResult.denied(ScanAuthorityOutcome.UNSUPPORTED,
                        ScanAuthorityReason.PROFILE_UNSUPPORTED);
            }
            var eligibleWindows = WindowsNtfsScanAuthority.capture(source, context, null, null);
            if (eligibleWindows.reason() != ScanAuthorityReason.AUTHORITY_UNAVAILABLE || context == null) {
                return eligibleWindows;
            }
            var route = io.github.topher6835.mediacompare.catalog.CurrentLocationAuthority
                    .requireCurrentHost(source, context);
            var anchor = WindowsNtfsPathInspector.inspect(route.anchor(), true);
            var root = WindowsNtfsPathInspector.inspect(route.root(), true);
            if (anchor.status() != HostFileStatus.ESTABLISHED || root.status() != HostFileStatus.ESTABLISHED) {
                HostFileStatus status = anchor.status() != HostFileStatus.ESTABLISHED
                        ? anchor.status() : root.status();
                return ScanAuthorityResult.denied(status == HostFileStatus.MISSING
                        ? ScanAuthorityOutcome.UNAVAILABLE : ScanAuthorityOutcome.UNCERTAIN,
                        status == HostFileStatus.MISSING
                                ? ScanAuthorityReason.AUTHORITY_UNAVAILABLE
                                : ScanAuthorityReason.AUTHORITY_UNCERTAIN);
            }
            return WindowsNtfsScanAuthority.capture(source, context, anchor.identity(), root.identity());
        }
        if (!LocationDialect.UNIX.persistedName().equals(source.rootPathDialect())
                || !HostFileSystems.current().supportsProfile(FileSystemProfile.APFS)) {
            return ScanAuthorityResult.denied(ScanAuthorityOutcome.UNSUPPORTED, ScanAuthorityReason.PROFILE_UNSUPPORTED);
        }
        var eligible = ScanObservationAuthority.capture(source, context, null, null);
        if (context == null || eligible.reason() != ScanAuthorityReason.AUTHORITY_UNAVAILABLE) {
            return eligible;
        }
        var acceptance = LocationContextAcceptanceAuthority.requireCurrentAccepted(context);
        var binding = SourceBindingAuthority.requireCurrentBound(source);
        var contextProbe = probe.captureLocationContext(
                acceptance.macOsApfsEvidence().anchorLocationPath());
        var rootProbe = probe.captureSourceRoot(new MacOsApfsSourceRootProbeRequest(
                context.id(), context.revision(), source.locationRevision(),
                acceptance.macOsApfsEvidence(),
                binding.macOsApfsSourceRootEvidence().rootLocationPath()));
        return ScanObservationAuthority.capture(source, context, contextProbe, rootProbe);
    }
}
