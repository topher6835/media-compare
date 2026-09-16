package io.github.topher6835.mediacompare.matching;

import java.util.List;

public record ExactDuplicateGroupDetails(
        ExactDuplicateGroupSummary summary,
        List<ExactDuplicateMember> members,
        List<ExactDuplicateOccurrence> occurrences) {
}
