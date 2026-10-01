package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.scan.authority.SourceRelativePath;

import org.springframework.stereotype.Component;

/** Rechecks the current durable Source/context pair under a caller's write transaction. */
@Component
public class CurrentMembershipAuthority {
    private final CatalogRepository catalog;
    private final LocationContextRepository contexts;
    private final SourceMembershipRepository memberships;

    public CurrentMembershipAuthority(CatalogRepository catalog, LocationContextRepository contexts,
            SourceMembershipRepository memberships) {
        this.catalog = catalog;
        this.contexts = contexts;
        this.memberships = memberships;
    }

    /** Durable route eligibility shared by publication and read-only preflight. */
    public static boolean isCurrentRoute(FileEntry entry, SourceMembership member,
            Source source, LocationContext context) {
        return context != null && "RESOLVED".equals(entry.locationIdentityStatus())
                && context.id().equals(source.boundLocationContextId())
                && context.id().equals(entry.locationContextId())
                && entry.locationPath() != null && entry.locationKey() != null
                && member.fileEntryId() == entry.id() && member.sourceId() == source.id()
                && "ACTIVE".equals(member.applicabilityStatus())
                && "PRESENT".equals(member.presenceStatus())
                && member.observedFileEntryRevision() == entry.observationRevision()
                && member.observedSourceLocationRevision() != null
                && member.observedLocationContextRevision() != null
                && member.observedSourceLocationRevision() == source.locationRevision()
                && member.observedLocationContextRevision() == context.revision();
    }

    public void reserveAndRequire(long sourceId, long sourceRevision,
            String contextId, long contextRevision, long fileEntryId, long membershipId) {
        contexts.reserveWrite();
        Source source = catalog.findSourceById(sourceId).orElseThrow();
        LocationContext context = contexts.findById(contextId).orElseThrow();
        if (source.locationRevision() != sourceRevision
                || context.revision() != contextRevision
                || !contextId.equals(source.boundLocationContextId())) {
            throw new IllegalStateException("Source or LocationContext authority changed");
        }
        var route = CurrentLocationAuthority.requireCurrentHost(source, context);
        FileEntry entry = memberships.findById(fileEntryId).orElseThrow();
        SourceMembership member = memberships.findMembershipById(membershipId).orElseThrow();
        if (!isCurrentRoute(entry, member, source, context)) {
            throw new IllegalStateException("FileEntry lacks current trusted SourceMembership");
        }
        try {
            var path = new LocationPathCodec().decode(entry.locationPath());
            if (!LocationKeyCodec.matches(path, LocationKey.parse(entry.locationKey()))
                    || !route.anchor().contains(path)
                    || !route.root().contains(path)
                    || !member.relativePath().equals(SourceRelativePath.from(
                            route.root(), path))
                    || !member.pathKey().equals(member.relativePath())) {
                throw new IllegalStateException("Resolved FileEntry or membership path disagrees with authority");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved FileEntry or membership path is malformed", exception);
        }
    }
}
