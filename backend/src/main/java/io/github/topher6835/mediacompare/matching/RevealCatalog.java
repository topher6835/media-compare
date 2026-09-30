package io.github.topher6835.mediacompare.matching;

import java.util.List;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One short durable snapshot of a physical FileEntry and its current Source routes. */
@Component
public class RevealCatalog {
    private final SourceMembershipRepository memberships;
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;

    public RevealCatalog(SourceMembershipRepository memberships, CatalogRepository sources,
            LocationContextRepository contexts) {
        this.memberships = memberships;
        this.sources = sources;
        this.contexts = contexts;
    }

    @Transactional(readOnly = true)
    public CleanupPreflightCatalog.PhysicalFile capture(long fileEntryId) {
        var entry = memberships.findById(fileEntryId).orElse(null);
        if (entry == null) return null;
        List<CleanupPreflightCatalog.Route> routes = memberships.findActivePresentByFileEntryId(fileEntryId)
                .stream().map(member -> {
                    var source = sources.findSourceById(member.sourceId()).orElseThrow();
                    var context = source.boundLocationContextId() == null ? null
                            : contexts.findById(source.boundLocationContextId()).orElse(null);
                    return new CleanupPreflightCatalog.Route(member, source, context);
                }).toList();
        return new CleanupPreflightCatalog.PhysicalFile(entry, routes);
    }
}
