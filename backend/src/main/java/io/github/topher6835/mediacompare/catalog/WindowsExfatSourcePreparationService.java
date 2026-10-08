package io.github.topher6835.mediacompare.catalog;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityScope;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;
import io.github.topher6835.mediacompare.filesystem.ExfatRetainedRoot;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatHostFileSystem;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatSupport;
import io.github.topher6835.mediacompare.filesystem.WindowsFileAccessException;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsHostFileSystem;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;

/** Explicit acceptance only. The production constructor cannot enable exFAT acquisition. */
@Service
public class WindowsExfatSourcePreparationService {
    @FunctionalInterface interface Acquisition { ExfatRetainedRoot open(Path root) throws IOException; }
    private final CatalogRepository sources;
    private final WindowsExfatBindingWriter writer;
    private final ExfatAuthorityWindowRegistry registry;
    private final SessionSourceBoundary separation;
    private final Acquisition acquisition;
    private final WindowsExfatSupport support;

    @org.springframework.beans.factory.annotation.Autowired
    public WindowsExfatSourcePreparationService(CatalogRepository sources, WindowsExfatBindingWriter writer,
            ExfatAuthorityWindowRegistry registry, SessionSourceBoundary separation) {
        this(sources, writer, registry, separation, new WindowsExfatHostFileSystem()::openDirectoryChain,
                WindowsExfatSupport.PRODUCTION);
    }

    // Package-local seam uses the already established injected native support policy in tests.
    WindowsExfatSourcePreparationService(CatalogRepository sources, WindowsExfatBindingWriter writer,
            ExfatAuthorityWindowRegistry registry, SessionSourceBoundary separation,
            Acquisition acquisition, WindowsExfatSupport support) {
        this.sources = sources; this.writer = writer; this.registry = registry;
        this.separation = separation; this.acquisition = acquisition; this.support = support;
    }

    public Source prepare(long sourceId) { return accept(sourceId, null); }
    public Source rebind(long sourceId, String contextId) {
        if (contextId == null) throw new IllegalArgumentException("Rebind requires a context");
        return accept(sourceId, contextId);
    }

    private Source accept(long sourceId, String rebindContext) {
        Source source = sources.findSourceById(sourceId).orElseThrow(() -> new NoSuchElementException("Source missing"));
        SourcePreparationState state = SourcePreparationState.from(source);
        if (state == SourcePreparationState.REBIND_REQUIRED && rebindContext == null
                || rebindContext != null && state != SourcePreparationState.REBIND_REQUIRED) throw stateChanged();
        try {
            support.requireAvailable();
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                throw stateChanged();
            }
            var configured = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, source.rootPath());
            Path rootPath = Path.of(new WindowsNtfsHostFileSystem().pathText(configured));
            separation.requireSeparate(rootPath.toString());
            ExfatAuthorityScope existing = state == SourcePreparationState.READY ? writer.snapshot(sourceId) : null;
            if (existing != null && !existing.source().equals(source)) throw stateChanged();
            try (var attempt = registry.begin(sourceId, configured.components().size() + 1,
                    rebindContext != null ? rebindContext : source.boundLocationContextId())) {
                ExfatRetainedRoot acquired = null;
                try {
                    if (state == SourcePreparationState.READY) {
                        var scope = existing;
                        var projected = registry.project(scope);
                        if (projected.liveAuthorityAvailable()) {
                            var id = registry.capturePrepared(scope);
                            try (var operation = registry.requireExact(scope, id)) { operation.revalidate(); }
                            separation.requireSeparate(rootPath.toString());
                            registry.installing(attempt, () -> registry.retain(attempt, writer.reread(scope), id));
                            return scope.source();
                        }
                        if (registry.hasRetainedWindow(sourceId)) {
                            registry.invalidateSource(sourceId);
                            throw uncertain(); // A separate explicit Prepare is required after invalidation.
                        }
                    }
                    // A stale window is not replaced through the idempotent branch.
                    // Fresh acquisition keeps all root handles owned until installation or failure cleanup.
                    acquired = acquisition.open(rootPath);
                    acquired.revalidate();
                    attempt.checkpoint();
                    separation.requireSeparate(rootPath.toString());
                    var resolved = acquired.resolvedRoot();
                    var volume = acquired.volumeEvidence();
                    if (existing != null) {
                        var evidence = new WindowsExfatEvidenceCodec().decodeSource(source.bindingEvidenceJson());
                        if (!new io.github.topher6835.mediacompare.location.LocationPathCodec()
                                .decode(evidence.resolvedRootLocationPath()).equals(resolved)
                                || !evidence.volume().equals(volume)) throw uncertain();
                    }
                    ExfatRetainedRoot installingRoot = acquired;
                    ExfatAuthorityScope accepted = registry.installing(attempt, () -> {
                        long now = Math.max(System.currentTimeMillis(), source.updatedAtMs());
                        var committed = existing != null ? writer.reread(existing)
                                : rebindContext == null ? writer.firstBind(source, resolved, volume, now)
                                : writer.rebind(source, rebindContext, resolved, volume, now);
                        registry.install(attempt, committed, installingRoot);
                        return committed;
                    });
                    acquired = null; // Registry now owns it.
                    return accepted.source();
                } finally {
                    // Keep the attempt registered/charged until rejected native resources have actually closed.
                    if (acquired != null) {
                        try { acquired.close(); }
                        catch (IOException | RuntimeException failure) {
                            registry.cleanupFailed();
                            throw new SourcePreparationException(SourcePreparationException.Code.PROBE_ERROR);
                        }
                    }
                }
            }
        } catch (WindowsFileAccessException failure) {
            throw new SourcePreparationException(switch (failure.reason()) {
                case UNSUPPORTED -> SourcePreparationException.Code.PROFILE_UNSUPPORTED;
                case UNAVAILABLE -> SourcePreparationException.Code.PATH_UNAVAILABLE;
                case UNCERTAIN -> SourcePreparationException.Code.EVIDENCE_UNCERTAIN;
                case NATIVE_ERROR -> SourcePreparationException.Code.PROBE_ERROR;
            });
        } catch (NoSuchFileException failure) {
            throw new SourcePreparationException(SourcePreparationException.Code.PATH_UNAVAILABLE);
        } catch (IOException failure) {
            throw new SourcePreparationException(SourcePreparationException.Code.PROBE_ERROR);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw uncertain();
        }
    }

    /** Catalog/registry transient projection; production acquisition remains gated. */
    public ExfatAuthorityWindowRegistry.LiveAuthority liveAuthority(long sourceId) {
        try { return registry.project(writer.snapshot(sourceId)); }
        catch (IllegalArgumentException | IllegalStateException | NoSuchElementException failure) {
            return new ExfatAuthorityWindowRegistry.LiveAuthority(false, null);
        }
    }

    private static SourcePreparationException stateChanged() {
        return new SourcePreparationException(SourcePreparationException.Code.STATE_CHANGED);
    }
    private static SourcePreparationException uncertain() {
        return new SourcePreparationException(SourcePreparationException.Code.EVIDENCE_UNCERTAIN);
    }
}
