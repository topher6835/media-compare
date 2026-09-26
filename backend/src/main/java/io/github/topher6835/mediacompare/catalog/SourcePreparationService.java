package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.topher6835.mediacompare.location.ContinuityOutcome;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsContinuityVerifier;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootProbeRequest;

/** Coordinates explicit first-time preparation without holding a transaction across probes. */
@Service
public class SourcePreparationService {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final LocationContextActivationService activation;
    private final LocationContextAcceptanceService acceptance;
    private final SourceBindingService binding;
    private final SourcePreparationProbe probe;
    private final LocationPathCodec paths = new LocationPathCodec();

    public SourcePreparationService(CatalogRepository sources, LocationContextRepository contexts,
            LocationContextActivationService activation, LocationContextAcceptanceService acceptance,
            SourceBindingService binding, SourcePreparationProbe probe) {
        this.sources = sources;
        this.contexts = contexts;
        this.activation = activation;
        this.acceptance = acceptance;
        this.binding = binding;
        this.probe = probe;
    }

    public Source prepare(long sourceId) {
        Source source = sources.findSourceById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source " + sourceId + " does not exist"));
        SourcePreparationState state = SourcePreparationState.from(source);
        if (state == SourcePreparationState.READY) {
            return source;
        }
        if (state == SourcePreparationState.REBIND_REQUIRED) {
            throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
        }

        final LocationPath root;
        try {
            root = LocationPathParser.parse(LocationDialect.UNIX, source.rootPath());
        } catch (IllegalArgumentException exception) {
            throw new SourcePreparationException(SourcePreparationException.Code.PROFILE_UNSUPPORTED);
        }
        LocationPath logicalAnchor = accepted(probe.resolveAnchor(root));
        if (!logicalAnchor.contains(root)) {
            throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
        }
        LocationContext context = findApplicableContext(root, logicalAnchor);
        if (context == null) {
            long now = System.currentTimeMillis();
            context = activation.createActive(new LocationContext(
                    UUID.randomUUID().toString(), paths.encode(logicalAnchor),
                    LocationKeyCodec.encode(logicalAnchor).value(),
                    LocationContext.LifecycleStatus.ACTIVE,
                    LocationContext.ContinuityStatus.REVIEW_REQUIRED,
                    0, null, now, now));
        }

        if (context.continuityStatus() == LocationContext.ContinuityStatus.REVIEW_REQUIRED) {
            ContinuityProbeResult<MacOsApfsLocationContextEvidence> observation =
                    probe.captureContext(anchor(context));
            accepted(observation);
            try {
                context = acceptance.acceptReviewRequired(context.id(), context.revision(),
                        Math.max(System.currentTimeMillis(), context.updatedAtMs()), observation);
            } catch (NoSuchElementException exception) {
                throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
            } catch (IllegalArgumentException exception) {
                throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
            }
        }

        Source current = sources.findSourceById(sourceId)
                .orElseThrow(() -> new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED));
        if (!source.equals(current)) {
            throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
        }
        LocationContext currentContext = contexts.findById(context.id())
                .orElseThrow(() -> new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED));
        if (!context.equals(currentContext)) {
            throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
        }
        var acceptedAuthority = LocationContextAcceptanceAuthority.requireCurrentAccepted(currentContext);
        ContinuityProbeResult<MacOsApfsLocationContextEvidence> freshContextResult =
                probe.captureContext(acceptedAuthority.macOsApfsEvidence().anchorLocationPath());
        MacOsApfsLocationContextEvidence freshContext = accepted(freshContextResult);
        if (MacOsApfsContinuityVerifier.verifyLocationContext(
                acceptedAuthority.macOsApfsEvidence(), freshContext).outcome() != ContinuityOutcome.ACCEPTED) {
            throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
        }
        if (source.locationRevision() == Long.MAX_VALUE) {
            throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
        }
        var rootRequest = new MacOsApfsSourceRootProbeRequest(
                context.id(), context.revision(), source.locationRevision() + 1, freshContext, root);
        var rootResult = probe.captureRoot(rootRequest);
        accepted(rootResult);
        try {
            return binding.bindUnboundSource(sourceId, source.locationRevision(), context.id(), context.revision(),
                    Math.max(System.currentTimeMillis(), source.updatedAtMs()),
                    new SourceBindingCapture(sourceId, source.rootPath(), freshContextResult, rootResult));
        } catch (NoSuchElementException exception) {
            throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
        } catch (IllegalArgumentException exception) {
            throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
        }
    }

    private LocationContext findApplicableContext(LocationPath root, LocationPath logicalAnchor) {
        LocationContext applicable = null;
        boolean overlapping = false;
        for (LocationContext context : contexts.findActive()) {
            LocationPath existingAnchor = anchor(context);
            if (context.continuityStatus() == LocationContext.ContinuityStatus.ACCEPTED) {
                LocationContextAcceptanceAuthority.requireCurrentAccepted(context);
            } else if (context.continuityEvidenceJson() != null) {
                throw new IllegalStateException("Review-required context has continuity evidence");
            }
            if (existingAnchor.contains(root)) {
                if (!logicalAnchor.contains(existingAnchor) || applicable != null) {
                    throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
                }
                applicable = context;
            } else if (LocationAnchorPolicy.structurallyOverlaps(existingAnchor, logicalAnchor)) {
                overlapping = true;
            }
        }
        if (applicable == null && overlapping) {
            throw new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
        }
        return applicable;
    }

    private static LocationPath anchor(LocationContext context) {
        return LocationAnchorPolicy.validateAnchor(context.anchorLocationPath(), context.anchorLocationKey());
    }

    private static <T> T accepted(ContinuityProbeResult<T> result) {
        Objects.requireNonNull(result, "Probe result");
        if (result.outcome() == ContinuityOutcome.ACCEPTED) {
            return result.evidence().orElseThrow();
        }
        SourcePreparationException.Code code = switch (result.outcome()) {
            case UNAVAILABLE -> SourcePreparationException.Code.PATH_UNAVAILABLE;
            case UNSUPPORTED -> SourcePreparationException.Code.PROFILE_UNSUPPORTED;
            case UNCERTAIN, MISMATCH -> SourcePreparationException.Code.EVIDENCE_UNCERTAIN;
            case ERROR -> SourcePreparationException.Code.PROBE_ERROR;
            case ACCEPTED -> throw new IllegalStateException("Accepted probe lacks evidence");
        };
        throw new SourcePreparationException(code);
    }
}
