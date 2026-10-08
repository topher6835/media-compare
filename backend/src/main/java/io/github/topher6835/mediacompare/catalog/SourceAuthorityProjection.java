package io.github.topher6835.mediacompare.catalog;

import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import io.github.topher6835.mediacompare.filesystem.*;

/** Catalog and registry only: reading this projection never probes, acquires or repairs authority. */
@Service
public class SourceAuthorityProjection {
    public record Authority(FileSystemProfile filesystemProfile, Boolean liveAuthorityAvailable,
            String liveAuthorityWindowId) { }
    private final CatalogRepository sources;
    private final SourceBindingPeriodRepository periods;
    private final WindowsExfatBindingWriter writer;
    private final ExfatAuthorityWindowRegistry registry;

    public SourceAuthorityProjection(CatalogRepository sources, SourceBindingPeriodRepository periods,
            WindowsExfatBindingWriter writer, ExfatAuthorityWindowRegistry registry) {
        this.sources = sources; this.periods = periods; this.writer = writer; this.registry = registry;
    }

    public Authority project(Source source) {
        String evidence = source.bindingEvidenceJson();
        if (evidence == null) evidence = periods.findLatestBySourceId(source.id())
                .map(SourceBindingPeriod::bindingEvidenceJson).orElse(null);
        if (evidence == null) return new Authority(null, null, null);
        FileSystemProfile profile;
        if ("win-drive".equals(source.rootPathDialect())) {
            profile = WindowsDurableEvidenceFormat.sourceProfile(evidence);
        } else {
            new io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec().decode(evidence);
            profile = FileSystemProfile.APFS;
        }
        if (profile != FileSystemProfile.EXFAT) return new Authority(profile, null, null);
        try {
            var scope = writer.snapshot(source.id());
            var live = source.equals(scope.source()) ? registry.project(scope)
                    : new ExfatAuthorityWindowRegistry.LiveAuthority(false, null);
            return new Authority(profile, live.liveAuthorityAvailable(), live.liveAuthorityWindowId());
        } catch (IllegalArgumentException | IllegalStateException | NoSuchElementException unavailable) {
            return new Authority(profile, false, null);
        }
    }

    public ExfatAuthorityWindowRegistry.ReleaseResult release(long sourceId, String windowId) {
        Source source = sources.findSourceById(sourceId).orElseThrow(() -> new NoSuchElementException("Source missing"));
        // Context is supporting volume evidence for idempotent old releases, never the release target.
        String context = source.boundLocationContextId();
        if (context == null) context = periods.findLatestBySourceId(sourceId)
                .map(SourceBindingPeriod::locationContextId).orElse(null);
        return registry.release(new ExfatAuthorityWindowRegistry.WindowId(registry.runtimeId(), sourceId, windowId), context);
    }
}
