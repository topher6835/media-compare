package io.github.topher6835.mediacompare.catalog;

public record ContentAssignmentCandidate(
        long fileEntryId,
        long membershipId,
        long sourceId,
        String contextId,
        long sourceLocationRevision,
        long contextRevision,
        long membershipRevision,
        long observationRevision,
        long sizeBytes) {
}
