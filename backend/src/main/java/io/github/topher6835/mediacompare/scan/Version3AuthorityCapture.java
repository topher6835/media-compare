package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityProbe;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootProbeRequest;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthorityResult;
import io.github.topher6835.mediacompare.scan.authority.ScanAuthoritySnapshot;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;

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
        if (context == null) {
            return ScanObservationAuthority.capture(source, null, null, null);
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
