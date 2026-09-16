package io.github.topher6835.mediacompare.matching;

record ExactDuplicateOccurrenceRow(
        long fileEntryId,
        long contentRecordId,
        long sourceId,
        String sourceName,
        String relativePath,
        String extensionKey,
        String presenceStatus) {
}
