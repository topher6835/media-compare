package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Already captured exact-spelling file observation; no probe occurs here. */
public record ScanFileObservation(long sourceId, long sourceLocationRevision,
        String locationContextId, long locationContextRevision,
        LocationPath fileLocationPath, LocationKey fileLocationKey,
        String fileSystemType, String volumeUuid, ChildStorageBoundary childStorageBoundary,
        boolean regularFile, boolean symbolicLinkBoundary,
        long sizeBytes, long modifiedTimeEpochSecond, int modifiedTimeNano) {

    public ScanFileObservation {
        if (sourceId <= 0 || sourceLocationRevision < 0 || locationContextRevision < 0
                || sizeBytes < 0 || modifiedTimeNano < 0 || modifiedTimeNano > 999_999_999) {
            throw new IllegalArgumentException("File observation IDs, revisions, size, or mtime are invalid");
        }
        Objects.requireNonNull(locationContextId, "LocationContext ID");
        Objects.requireNonNull(fileLocationPath, "Exact file location");
        Objects.requireNonNull(fileLocationKey, "File location key");
        Objects.requireNonNull(childStorageBoundary, "Child storage boundary");
    }
}
