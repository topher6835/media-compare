package io.github.topher6835.mediacompare.matching;

import java.util.HashSet;
import java.util.List;

/** IDs select physical files; no client snapshot supplies authority. */
public record CleanupPreflightRequest(Long keeperFileEntryId, List<Long> candidateFileEntryIds) {
    public static final int MAX_CANDIDATES = 250;

    public CleanupPreflightRequest {
        if (keeperFileEntryId == null || keeperFileEntryId <= 0
                || candidateFileEntryIds == null || candidateFileEntryIds.isEmpty()
                || candidateFileEntryIds.size() > MAX_CANDIDATES
                || candidateFileEntryIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(candidateFileEntryIds).size() != candidateFileEntryIds.size()
                || candidateFileEntryIds.contains(keeperFileEntryId)) {
            throw new IllegalArgumentException("Require one positive keeper and 1–250 distinct positive candidates");
        }
        candidateFileEntryIds = List.copyOf(candidateFileEntryIds);
    }
}
