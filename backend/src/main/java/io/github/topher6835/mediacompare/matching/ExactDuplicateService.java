package io.github.topher6835.mediacompare.matching;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;

import org.springframework.stereotype.Service;

@Service
public class ExactDuplicateService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 250;

    private final ExactDuplicateRepository repository;

    public ExactDuplicateService(ExactDuplicateRepository repository) {
        this.repository = repository;
    }

    public ExactDuplicateGroupPage findGroups(String afterDigestHex, Integer requestedLimit) {
        String cursor = validateOptionalDigest(afterDigestHex);
        int limit = validateLimit(requestedLimit);
        validateIntegrity();

        List<ExactDuplicateGroupSummary> results = repository.findGroupCounts(cursor, limit + 1).stream()
                .map(this::toSummary)
                .toList();
        boolean hasNextPage = results.size() > limit;
        List<ExactDuplicateGroupSummary> page = hasNextPage
                ? List.copyOf(results.subList(0, limit))
                : results;
        String nextCursor = hasNextPage ? page.getLast().digestHex() : null;
        return new ExactDuplicateGroupPage(page, nextCursor);
    }

    public Optional<ExactDuplicateGroupDetails> findGroup(String digestHex) {
        validateDigest(digestHex);
        validateIntegrity();
        return repository.findGroupCounts(digestHex)
                .map(this::toSummary)
                .map(summary -> new ExactDuplicateGroupDetails(
                        summary,
                        List.copyOf(repository.findMembers(digestHex)),
                        List.copyOf(repository.findOccurrences(digestHex))));
    }

    private void validateIntegrity() {
        OptionalLong invalidArtifactId = repository.findFirstInvalidCompletedExactArtifactId();
        if (invalidArtifactId.isPresent()) {
            throw new ExactDuplicateIntegrityException(
                    "Completed exact AnalysisRecord " + invalidArtifactId.getAsLong() + " is invalid");
        }
        repository.findFirstSizeMismatchedDigest().ifPresent(digest -> {
            throw new ExactDuplicateIntegrityException(
                    "ContentRecords in exact digest group " + digest + " disagree on size");
        });
    }

    private ExactDuplicateGroupSummary toSummary(ExactDuplicateGroupCounts counts) {
        long removableOccurrences = Math.max(counts.presentOccurrenceCount() - 1, 0);
        long potentialStorageSavingsBytes;
        try {
            potentialStorageSavingsBytes = Math.multiplyExact(removableOccurrences, counts.sizeBytes());
        } catch (ArithmeticException exception) {
            throw new ExactDuplicateIntegrityException(
                    "Potential storage savings overflow for digest " + counts.digestHex());
        }
        return new ExactDuplicateGroupSummary(
                counts.digestHex(),
                counts.sizeBytes(),
                counts.contentRecordCount(),
                counts.contentRecordCount() - 1,
                counts.presentOccurrenceCount(),
                counts.missingOccurrenceCount(),
                counts.sourceCount(),
                potentialStorageSavingsBytes);
    }

    private static String validateOptionalDigest(String digestHex) {
        if (digestHex == null) {
            return "";
        }
        validateDigest(digestHex);
        return digestHex;
    }

    private static void validateDigest(String digestHex) {
        if (!Sha256AnalysisDefinition.isValidDigest(digestHex)) {
            throw new IllegalArgumentException(
                    "Digest must be lowercase 64-character SHA-256 hexadecimal");
        }
    }

    private static int validateLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIMIT);
        }
        return requestedLimit;
    }
}
