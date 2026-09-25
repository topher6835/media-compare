package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityVerifier;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootComparisonContext;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;

/** Shared APFS evidence checks for binding, rebinding, and explicit relocation. */
final class SourceBindingValidation {
    private final SourceBindingEvidenceCodec bindingCodec = new SourceBindingEvidenceCodec();

    ValidatedBinding validate(Source source, String intendedRootPath, LocationContext context,
            LocationContextAcceptanceEvidence acceptance, SourceBindingCapture capture,
            long boundRevision) {
        if (capture.contextProbeResult().outcome() != ContinuityOutcome.ACCEPTED) {
            throw new IllegalArgumentException("Fresh context probe must be accepted");
        }
        MacOsApfsLocationContextEvidence freshContext = capture.contextProbeResult().evidence().orElseThrow();
        MacOsApfsLocationContextEvidence baseline = acceptance.macOsApfsEvidence();
        if (MacOsApfsContinuityVerifier.verifyLocationContext(baseline, freshContext).outcome()
                != ContinuityOutcome.ACCEPTED) {
            throw new IllegalArgumentException("Fresh context observation does not match accepted baseline");
        }
        if (capture.sourceRootProbeResult().outcome() != ContinuityOutcome.ACCEPTED) {
            throw new IllegalArgumentException("Source-root probe must be accepted");
        }
        MacOsApfsSourceRootEvidence rootEvidence = capture.sourceRootProbeResult().evidence().orElseThrow();
        if (capture.sourceId() != source.id()
                || !capture.configuredRootPathSnapshot().equals(intendedRootPath)) {
            throw new IllegalArgumentException("Source binding capture does not match current Source");
        }
        LocationPath configuredRoot = LocationPathParser.parse(LocationDialect.UNIX, intendedRootPath);
        if (!configuredRoot.equals(rootEvidence.rootLocationPath())) {
            throw new IllegalArgumentException("Configured Source root differs from exact observed spelling");
        }
        var comparison = new MacOsApfsSourceRootComparisonContext(
                context.id(), context.revision(), boundRevision, baseline);
        if (MacOsApfsContinuityVerifier.validateInitialSourceRoot(comparison, rootEvidence).outcome()
                != ContinuityOutcome.ACCEPTED) {
            throw new IllegalArgumentException("Source-root evidence does not match accepted APFS context");
        }

        String rootKey = LocationKeyCodec.encode(rootEvidence.rootLocationPath()).value();
        String evidenceJson = bindingCodec.encode(new SourceBindingEvidence(
                SourceBindingEvidence.VERSION, source.id(), rootEvidence));
        return new ValidatedBinding(rootKey, evidenceJson);
    }

    record ValidatedBinding(String rootKey, String evidenceJson) {
    }
}
