package io.github.topher6835.mediacompare.matching;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static io.github.topher6835.mediacompare.matching.CleanupPreflightReason.*;

@Service
public class CleanupPreflightService {
    private final CleanupPreflightCatalog catalog;
    private final CleanupPreflightFileValidator validator;

    public CleanupPreflightService(CleanupPreflightCatalog catalog, CleanupPreflightFileValidator validator) {
        this.catalog = catalog;
        this.validator = validator;
    }

    public CleanupPreflightResponse preflight(String digest, CleanupPreflightRequest request) {
        if (!Sha256AnalysisDefinition.isValidDigest(digest) || request == null) {
            throw new IllegalArgumentException("Require a lowercase SHA-256 digest and request");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cleanup preflight cannot run inside a database transaction");
        }
        var before = catalog.capture(digest);
        var groupFailure = groupFailure(before, request);
        if (groupFailure != null) return blocked(digest, request, before, groupFailure);

        List<CleanupPreflightResponse.FileResult> results = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        ids.add(request.keeperFileEntryId());
        ids.addAll(request.candidateFileEntryIds());
        for (long id : ids) {
            results.add(CleanupPreflightResponse.FileResult.result(id,
                    validator.validate(before.files().get(id), digest)));
        }
        var after = catalog.capture(digest);
        groupFailure = groupFailure(after, request);
        if (groupFailure == null && !before.group().equals(after.group())) groupFailure = GROUP_CHANGED;
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            if (!Objects.equals(before.files().get(id), after.files().get(id))) {
                results.set(i, CleanupPreflightResponse.FileResult.result(id, AUTHORITY_CHANGED));
            }
        }
        if (groupFailure == null) {
            groupFailure = results.stream().map(CleanupPreflightResponse.FileResult::reason)
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }
        Long savings = groupFailure == null
                ? Math.multiplyExact((long) request.candidateFileEntryIds().size(), after.group().summary().sizeBytes())
                : null;
        return new CleanupPreflightResponse(digest, groupFailure == null ? CleanupPreflightStatus.READY
                : CleanupPreflightStatus.BLOCKED, groupFailure, before.group().summary().sizeBytes(),
                request.candidateFileEntryIds().size(), savings, results.getFirst(),
                List.copyOf(results.subList(1, results.size())));
    }

    private static CleanupPreflightReason groupFailure(CleanupPreflightCatalog.Snapshot snapshot,
            CleanupPreflightRequest request) {
        if (snapshot.group() == null) return GROUP_CHANGED;
        if (!snapshot.files().containsKey(request.keeperFileEntryId())) return KEEPER_UNAVAILABLE;
        var others = new HashSet<>(snapshot.files().keySet());
        others.remove(request.keeperFileEntryId());
        if (!others.equals(new HashSet<>(request.candidateFileEntryIds()))) return CANDIDATE_SET_CHANGED;
        if (snapshot.files().size() < 2) return GROUP_CHANGED;
        return null;
    }

    private static CleanupPreflightResponse blocked(String digest, CleanupPreflightRequest request,
            CleanupPreflightCatalog.Snapshot snapshot, CleanupPreflightReason reason) {
        return new CleanupPreflightResponse(digest, CleanupPreflightStatus.BLOCKED, reason,
                snapshot.group() == null ? null : snapshot.group().summary().sizeBytes(),
                request.candidateFileEntryIds().size(), null,
                CleanupPreflightResponse.FileResult.result(request.keeperFileEntryId(), reason),
                request.candidateFileEntryIds().stream()
                        .map(id -> CleanupPreflightResponse.FileResult.result(id, reason)).toList());
    }
}
