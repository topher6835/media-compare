package io.github.topher6835.mediacompare.preview;

import java.util.Objects;

import io.github.topher6835.mediacompare.catalog.FileEntry;

/** Cache identity evidence, not authority to open original media. */
public record PreviewSourceEvidence(
        long fileEntryId,
        long contentRecordId,
        long observationRevision,
        long sizeBytes,
        long modifiedTimeEpochSecond,
        int modifiedTimeNano) {

    public PreviewSourceEvidence {
        if (fileEntryId <= 0 || contentRecordId <= 0) {
            throw new IllegalArgumentException("FileEntry and ContentRecord IDs must be positive");
        }
        if (observationRevision < 0 || sizeBytes < 0) {
            throw new IllegalArgumentException("Revision and size must be nonnegative");
        }
        if (modifiedTimeNano < 0 || modifiedTimeNano > 999_999_999) {
            throw new IllegalArgumentException("Modified time nanosecond must be within 0..999999999");
        }
    }

    public static PreviewSourceEvidence from(FileEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.id() == null || entry.currentContentId() == null
                || entry.modifiedTimeEpochSecond() == null || entry.modifiedTimeNano() == null) {
            throw new IllegalArgumentException("Preview identity requires persisted IDs and complete modification time");
        }
        return new PreviewSourceEvidence(entry.id(), entry.currentContentId(), entry.observationRevision(),
                entry.sizeBytes(), entry.modifiedTimeEpochSecond(), entry.modifiedTimeNano());
    }
}
