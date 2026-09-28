package io.github.topher6835.mediacompare.matching;

import java.util.List;

/** Informational, immediately stale; never permission for a future file operation. */
public record CleanupPreflightResponse(String digestHex, CleanupPreflightStatus status,
        CleanupPreflightReason reason, Long sizeBytes, int candidateCount,
        Long estimatedSavingsBytes, FileResult keeper, List<FileResult> candidates) {
    public record FileResult(long fileEntryId, CleanupPreflightStatus status,
            CleanupPreflightReason reason) {
        static FileResult result(long id, CleanupPreflightReason reason) {
            return new FileResult(id, reason == null ? CleanupPreflightStatus.READY
                    : CleanupPreflightStatus.BLOCKED, reason);
        }
    }
}
