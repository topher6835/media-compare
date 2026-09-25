package io.github.topher6835.mediacompare.scan;

import java.util.List;
import java.util.Map;

import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobStage;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Version3DiscoveryCompletionWriter {
    private final LocationContextRepository contexts;
    private final SourceMembershipPublicationService memberships;
    private final DiscoveryExecutionState state;

    public Version3DiscoveryCompletionWriter(LocationContextRepository contexts,
            SourceMembershipPublicationService memberships, DiscoveryExecutionState state) {
        this.contexts = contexts;
        this.memberships = memberships;
        this.state = state;
    }

    @Transactional
    public void complete(Job job, JobStage stage, List<DiscoverySource> sources,
            Map<Long, MissingClaimAuthority> claims, long count, long completedAtMs) {
        contexts.reserveWrite();
        if (claims.size() != sources.size()) {
            throw new IllegalStateException("Missing trusted traversal authority");
        }
        for (DiscoverySource source : sources) {
            MissingClaimAuthority claim = claims.get(source.source().id());
            if (claim == null || claim.sourceLocationRevision() != source.source().locationRevision()) {
                throw new IllegalStateException("Missing or stale Source traversal authority");
            }
            memberships.requireCurrent(claim.sourceId(), claim.sourceLocationRevision(),
                    claim.locationContextId(), claim.locationContextRevision());
        }
        state.complete(job, stage, sources, count, completedAtMs);
    }
}
