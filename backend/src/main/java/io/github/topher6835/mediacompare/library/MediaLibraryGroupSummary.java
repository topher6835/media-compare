package io.github.topher6835.mediacompare.library;

/** Derived component key, lowest current physical item, and current physical-item count. */
public record MediaLibraryGroupSummary(long groupKeyContentRecordId, MediaLibraryItem representative,
        long currentItemCount) {
}
