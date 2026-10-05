package io.github.topher6835.mediacompare.matching;

record ExactDuplicateOccurrenceRow(
        long fileEntryId,
        long contentRecordId,
        long membershipId,
        long sourceId,
        String sourceName,
        String relativePath,
        String extensionKey,
        String presenceStatus,
        String applicabilityStatus,
        String absolutePath, boolean physicalActionsAvailable, String physicalActionsUnavailableReason) {
    public ExactDuplicateOccurrenceRow(
        long fileEntryId,
        long contentRecordId,
        long membershipId,
        long sourceId,
        String sourceName,
        String relativePath,
        String extensionKey,
        String presenceStatus,
        String applicabilityStatus,
        String absolutePath) {
        this(fileEntryId, contentRecordId, membershipId, sourceId, sourceName, relativePath, extensionKey, presenceStatus, applicabilityStatus, absolutePath, absolutePath != null, absolutePath == null ? "AUTHORITY_UNAVAILABLE" : null);
    }

}
