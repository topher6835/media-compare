package io.github.topher6835.mediacompare.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.catalog.FileCategory;
import io.github.topher6835.mediacompare.library.MediaLibraryRepository;
import io.github.topher6835.mediacompare.library.MediaLibraryItem;

import org.springframework.stereotype.Service;

@Service
public class ExactDuplicateService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 250;

    private final ExactDuplicateRepository repository;
    private final MediaLibraryRepository library;

    public ExactDuplicateService(ExactDuplicateRepository repository, MediaLibraryRepository library) {
        this.repository = repository;
        this.library = library;
    }

    public ExactDuplicateGroupPage findGroups(String afterDigestHex, Integer requestedLimit) {
        return findGroups(afterDigestHex, requestedLimit, ExactDuplicateFilter.none());
    }

    public ExactDuplicateGroupPage findGroups(
            String afterDigestHex, Integer requestedLimit, ExactDuplicateFilter filter) {
        String cursor = validateOptionalDigest(afterDigestHex);
        int limit = validateLimit(requestedLimit);
        repository.validateIntegrity();

        if (filter.active() && filter.effectiveExtensionKeys().isEmpty()) {
            return new ExactDuplicateGroupPage(List.of(), null);
        }

        List<ExactDuplicateGroupCounts> results = repository.findGroupCounts(cursor, limit + 1, filter);
        boolean hasNextPage = results.size() > limit;
        List<ExactDuplicateGroupCounts> pageCounts = hasNextPage
                ? List.copyOf(results.subList(0, limit))
                : results;
        Map<String, ExactDuplicateFilterMatch> matches = filter.active()
                ? findFilterMatches(pageCounts, filter)
                : Map.of();
        Map<String, Long> representativeIds = repository.findRepresentativeImageFileIds(
                pageCounts.stream().map(ExactDuplicateGroupCounts::digestHex).toList());
        Map<Long, MediaLibraryItem> representatives = new java.util.HashMap<>();
        for (MediaLibraryItem item : library.findByIds(representativeIds.values().stream().distinct().toList())) {
            representatives.put(item.fileEntryId(), item);
        }
        List<ExactDuplicateGroupSummary> page = pageCounts.stream()
                .map(counts -> toSummary(counts, matches.get(counts.digestHex()),
                        representatives.get(representativeIds.get(counts.digestHex()))))
                .toList();
        String nextCursor = hasNextPage ? page.getLast().digestHex() : null;
        return new ExactDuplicateGroupPage(page, nextCursor);
    }

    public Optional<ExactDuplicateGroupDetails> findGroup(String digestHex) {
        return findGroup(digestHex, ExactDuplicateFilter.none());
    }

    public Optional<ExactDuplicateGroupDetails> findGroup(
            String digestHex, ExactDuplicateFilter filter) {
        validateDigest(digestHex);
        repository.validateIntegrity();
        Map<String, Long> representativeIds = repository.findRepresentativeImageFileIds(List.of(digestHex));
        Long representativeId = representativeIds.get(digestHex);
        MediaLibraryItem representative = representativeId == null ? null
                : library.findByIds(List.of(representativeId)).stream().findFirst().orElse(null);
        return repository.findGroupCounts(digestHex)
                .map(counts -> toSummary(counts, null, representative))
                .map(summary -> new ExactDuplicateGroupDetails(
                        summary,
                        List.copyOf(repository.findMembers(digestHex)),
                        repository.findOccurrences(digestHex).stream()
                                .map(occurrence -> toOccurrence(occurrence, filter))
                                .toList()));
    }

    public List<ExactDuplicateFilterOption> findFilterOptions() {
        repository.validateIntegrity();
        return List.copyOf(repository.findFilterOptions());
    }

    private Map<String, ExactDuplicateFilterMatch> findFilterMatches(
            List<ExactDuplicateGroupCounts> pageCounts, ExactDuplicateFilter filter) {
        List<String> digests = pageCounts.stream().map(ExactDuplicateGroupCounts::digestHex).toList();
        Map<String, MutableFilterMatch> matches = new LinkedHashMap<>();
        for (ExactDuplicateFilterMatchCount count : repository.findFilterMatchCounts(
                digests, filter.effectiveExtensionKeys())) {
            MutableFilterMatch match = matches.computeIfAbsent(
                    count.digestHex(), ignored -> new MutableFilterMatch());
            match.occurrenceCount += count.occurrenceCount();
            match.extensionKeys.add(count.extensionKey());
        }

        Map<String, ExactDuplicateFilterMatch> result = new LinkedHashMap<>();
        matches.forEach((digest, match) -> result.put(digest, new ExactDuplicateFilterMatch(
                match.occurrenceCount, List.copyOf(match.extensionKeys))));
        return result;
    }

    private ExactDuplicateOccurrence toOccurrence(
            ExactDuplicateOccurrenceRow occurrence, ExactDuplicateFilter filter) {
        FileCategory category = FileCategory.fromExtensionKey(occurrence.extensionKey()).orElse(null);
        return new ExactDuplicateOccurrence(
                occurrence.fileEntryId(),
                occurrence.contentRecordId(),
                occurrence.membershipId(),
                occurrence.sourceId(),
                occurrence.sourceName(),
                occurrence.relativePath(),
                occurrence.presenceStatus(),
                occurrence.applicabilityStatus(),
                occurrence.extensionKey(),
                category,
                filter.matches(occurrence.extensionKey()),
                occurrence.absolutePath(), occurrence.physicalActionsAvailable(), occurrence.physicalActionsUnavailableReason());
    }

    private ExactDuplicateGroupSummary toSummary(
            ExactDuplicateGroupCounts counts, ExactDuplicateFilterMatch filterMatch, MediaLibraryItem representative) {
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
                potentialStorageSavingsBytes,
                filterMatch,
                representative);
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

    private static final class MutableFilterMatch {
        private long occurrenceCount;
        private final List<String> extensionKeys = new ArrayList<>();
    }
}
