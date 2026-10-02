package io.github.topher6835.mediacompare.catalog;

import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt;
import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceiptCodec;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;

/** Storage guards only. Profiles must come from validated context/Source envelopes, never path dialects. */
public final class OccurrenceProfileValidation {
    private OccurrenceProfileValidation() { }

    public static void requireNative(FileEntry entry) {
        if (entry.occurrenceToken() != null || entry.observationEvidenceJson() != null) {
            throw new IllegalArgumentException("Native FileEntry writes require null token and receipt");
        }
    }

    public static void requireResolved(FileSystemProfile contextProfile, FileSystemProfile sourceProfile,
            FileEntry entry) {
        if (!"RESOLVED".equals(entry.locationIdentityStatus()) || contextProfile != sourceProfile) {
            throw new IllegalArgumentException("Resolved FileEntry requires matching context/Source profiles");
        }
        if (contextProfile == FileSystemProfile.APFS || contextProfile == FileSystemProfile.NTFS) {
            requireNative(entry);
        } else if (contextProfile == FileSystemProfile.EXFAT) {
            requireExfat(entry);
        } else {
            throw new IllegalArgumentException("Unsupported occurrence profile");
        }
    }

    public static ExfatObservationReceipt requireExfat(FileEntry entry) {
        if (!"RESOLVED".equals(entry.locationIdentityStatus()) || entry.occurrenceToken() == null
                || entry.observationEvidenceJson() == null) {
            throw new IllegalArgumentException("exFAT resolved occurrences require token and receipt");
        }
        var receipt = new ExfatObservationReceiptCodec().decode(entry.observationEvidenceJson());
        if (!receipt.occurrenceToken().equals(entry.occurrenceToken())
                || !receipt.contextId().equals(entry.locationContextId())
                || !receipt.locationPath().equals(entry.locationPath())
                || !receipt.locationKey().equals(entry.locationKey())
                || receipt.fileObservationRevision() != entry.observationRevision()
                || receipt.sizeBytes() != entry.sizeBytes()
                || !Long.valueOf(receipt.modifiedTimeEpochSecond()).equals(entry.modifiedTimeEpochSecond())
                || !Integer.valueOf(receipt.modifiedTimeNano()).equals(entry.modifiedTimeNano())) {
            throw new IllegalArgumentException("FileEntry disagrees with exFAT receipt");
        }
        return receipt;
    }
}
