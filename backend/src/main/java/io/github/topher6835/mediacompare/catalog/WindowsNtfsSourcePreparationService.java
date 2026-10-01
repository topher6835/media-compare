package io.github.topher6835.mediacompare.catalog;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.topher6835.mediacompare.filesystem.HostFileStatus;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsIdentity;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsPathInspector;
import io.github.topher6835.mediacompare.location.LocationAnchorPolicy;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

/** Explicit local-drive preparation; no database transaction spans native observations. */
@Service
public class WindowsNtfsSourcePreparationService {
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;
    private final LocationContextActivationService activation;
    private final WindowsNtfsBindingWriter writer;
    private final WindowsNtfsEvidenceCodec codec = new WindowsNtfsEvidenceCodec();
    private final LocationPathCodec paths = new LocationPathCodec();

    public WindowsNtfsSourcePreparationService(CatalogRepository sources, LocationContextRepository contexts,
            LocationContextActivationService activation, WindowsNtfsBindingWriter writer) {
        this.sources = sources;
        this.contexts = contexts;
        this.activation = activation;
        this.writer = writer;
    }

    public Source prepare(Source source) {
        final LocationPath root;
        try {
            root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
        } catch (IllegalArgumentException exception) {
            throw new SourcePreparationException(SourcePreparationException.Code.PROFILE_UNSUPPORTED);
        }
        LocationPath anchor = new LocationPath(LocationDialect.WINDOWS_DRIVE, root.rootFields(), java.util.List.of());
        WindowsNtfsIdentity rootFirst = require(WindowsNtfsPathInspector.inspect(root, true));
        WindowsNtfsIdentity anchorFirst = require(WindowsNtfsPathInspector.inspect(anchor, true));
        if (!rootFirst.volumeSerial().equals(anchorFirst.volumeSerial())) {
            throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
        }
        LocationContext context = null;
        for (LocationContext active : contexts.findActive()) {
            LocationPath existing = LocationAnchorPolicy.validateAnchor(
                    active.anchorLocationPath(), active.anchorLocationKey());
            if (existing.dialect() != LocationDialect.WINDOWS_DRIVE) continue;
            if (existing.rootFields().getFirst().equalsIgnoreCase(anchor.rootFields().getFirst())) {
                if (!existing.equals(anchor) || context != null) {
                    throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
                }
                context = active;
            }
        }
        if (context == null) {
            long now = System.currentTimeMillis();
            context = activation.createActive(new LocationContext(UUID.randomUUID().toString(),
                    paths.encode(anchor), LocationKeyCodec.encode(anchor).value(),
                    LocationContext.LifecycleStatus.ACTIVE,
                    LocationContext.ContinuityStatus.REVIEW_REQUIRED, 0, null, now, now));
        }
        if (context.continuityStatus() == LocationContext.ContinuityStatus.REVIEW_REQUIRED) {
            WindowsNtfsIdentity observed = require(WindowsNtfsPathInspector.inspect(anchor, true));
            context = writer.accept(context.id(), context.revision(), observed,
                    Math.max(System.currentTimeMillis(), context.updatedAtMs()));
        }
        var baseline = codec.decodeContext(context.continuityEvidenceJson());
        if (!baseline.anchor().equals(LocationAnchorPolicy.validateAnchor(
                context.anchorLocationPath(), context.anchorLocationKey()))
                || !baseline.anchor().equals(anchor) || baseline.contextRevision() != context.revision()) {
            throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
        }
        WindowsNtfsIdentity anchorFresh = require(WindowsNtfsPathInspector.inspect(anchor, true));
        WindowsNtfsIdentity rootFresh = require(WindowsNtfsPathInspector.inspect(root, true));
        if (!baseline.identity().equals(anchorFresh) || !rootFirst.equals(rootFresh)
                || !rootFresh.volumeSerial().equals(anchorFresh.volumeSerial())) {
            throw new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
        }
        return writer.bind(source.id(), source.locationRevision(), source.rootPath(), context.id(),
                context.revision(), anchorFresh, rootFresh,
                Math.max(System.currentTimeMillis(), source.updatedAtMs()));
    }

    /** Explicit fixed-root rebinding after the existing unbind operation. */
    public Source rebind(long sourceId, String contextId) {
        Source source = sources.findSourceById(sourceId).orElseThrow();
        if (SourcePreparationState.from(source) != SourcePreparationState.REBIND_REQUIRED
                || !LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())) {
            throw new SourceRebindingConflictException(sourceId, "Source is not an unbound NTFS Source");
        }
        LocationContext context = contexts.findById(contextId).orElseThrow();
        LocationPath root = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
        LocationPath anchor = new LocationPath(LocationDialect.WINDOWS_DRIVE, root.rootFields(), java.util.List.of());
        var baseline = codec.decodeContext(context.continuityEvidenceJson());
        if (!baseline.anchor().equals(anchor) || !baseline.contextId().equals(contextId)
                || baseline.contextRevision() != context.revision()) {
            throw new SourceRebindingConflictException(sourceId, "NTFS target context differs from Source drive");
        }
        WindowsNtfsIdentity anchorObserved = require(WindowsNtfsPathInspector.inspect(anchor, true));
        WindowsNtfsIdentity rootObserved = require(WindowsNtfsPathInspector.inspect(root, true));
        if (!baseline.identity().equals(anchorObserved)
                || !anchorObserved.volumeSerial().equals(rootObserved.volumeSerial())) {
            throw new SourceRebindingConflictException(sourceId, "NTFS continuity changed");
        }
        return writer.rebind(sourceId, source.locationRevision(), contextId, context.revision(),
                anchorObserved, rootObserved, Math.max(System.currentTimeMillis(), source.updatedAtMs()));
    }

    private static WindowsNtfsIdentity require(WindowsNtfsPathInspector.Result result) {
        if (result.status() == HostFileStatus.ESTABLISHED) return result.identity();
        SourcePreparationException.Code code = switch (result.status()) {
            case MISSING -> SourcePreparationException.Code.PATH_UNAVAILABLE;
            case UNVERIFIABLE -> SourcePreparationException.Code.PROFILE_UNSUPPORTED;
            case STALE, UNSAFE_PATH -> SourcePreparationException.Code.EVIDENCE_UNCERTAIN;
            case ESTABLISHED -> throw new IllegalStateException();
        };
        throw new SourcePreparationException(code);
    }
}
