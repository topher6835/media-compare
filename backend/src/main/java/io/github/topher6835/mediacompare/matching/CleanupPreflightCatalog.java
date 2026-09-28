package io.github.topher6835.mediacompare.matching;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceMembership;
import io.github.topher6835.mediacompare.catalog.SourceMembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short coherent read snapshots. No writer reservation or filesystem access. */
@Component
public class CleanupPreflightCatalog {
    private final ExactDuplicateService duplicates;
    private final SourceMembershipRepository memberships;
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;

    public CleanupPreflightCatalog(ExactDuplicateService duplicates, SourceMembershipRepository memberships,
            CatalogRepository sources, LocationContextRepository contexts) {
        this.duplicates = duplicates;
        this.memberships = memberships;
        this.sources = sources;
        this.contexts = contexts;
    }

    @Transactional(readOnly = true)
    public Snapshot capture(String digest) {
        var group = duplicates.findGroup(digest).orElse(null);
        if (group == null) return new Snapshot(null, Map.of());
        Map<Long, PhysicalFile> files = new TreeMap<>();
        for (var occurrence : group.occurrences()) {
            if (!"ACTIVE".equals(occurrence.applicabilityStatus())
                    || !"PRESENT".equals(occurrence.presenceStatus())) continue;
            long id = occurrence.fileEntryId();
            if (files.containsKey(id)) continue;
            var entry = memberships.findById(id).orElseThrow();
            if (entry.currentContentId() == null || entry.currentContentId() != occurrence.contentRecordId()
                    || entry.sizeBytes() != group.summary().sizeBytes()) {
                throw new ExactDuplicateIntegrityException("Physical FileEntry disagrees with exact group evidence: " + id);
            }
            var routes = group.occurrences().stream()
                    .filter(o -> o.fileEntryId() == id && "ACTIVE".equals(o.applicabilityStatus())
                            && "PRESENT".equals(o.presenceStatus()))
                    .map(o -> {
                        var member = memberships.findMembershipById(o.membershipId()).orElseThrow();
                        var source = sources.findSourceById(member.sourceId()).orElseThrow();
                        var context = source.boundLocationContextId() == null ? null
                                : contexts.findById(source.boundLocationContextId()).orElseThrow();
                        return new Route(member, source, context);
                    }).toList();
            files.put(id, new PhysicalFile(entry, routes));
        }
        return new Snapshot(group, Map.copyOf(files));
    }

    public record Route(SourceMembership membership, Source source, LocationContext context) { }
    public record PhysicalFile(FileEntry entry, List<Route> routes) { }
    public record Snapshot(ExactDuplicateGroupDetails group, Map<Long, PhysicalFile> files) { }
}
