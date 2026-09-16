package io.github.topher6835.mediacompare.matching;

import java.util.List;

public record ExactDuplicateGroupPage(
        List<ExactDuplicateGroupSummary> groups,
        String nextAfterDigestHex) {
}
