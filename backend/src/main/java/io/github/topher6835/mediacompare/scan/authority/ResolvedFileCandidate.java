package io.github.topher6835.mediacompare.scan.authority;

import java.util.Objects;

import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;

/** Future physical-occurrence candidate; Source-relative fields describe its relationship. */
public record ResolvedFileCandidate(long sourceId, long sourceLocationRevision,
        String locationContextId, long locationContextRevision,
        LocationPath fileLocationPath, LocationKey fileLocationKey,
        String relativePath, String relativePathKey,
        String fileSystemType, String volumeUuid, ChildStorageBoundary childStorageBoundary,
        boolean regularFile, boolean symbolicLinkBoundary,
        long sizeBytes, long modifiedTimeEpochSecond, int modifiedTimeNano) {

    public ResolvedFileCandidate {
        if (sourceId <= 0 || sourceLocationRevision < 0 || locationContextRevision < 0
                || sizeBytes < 0 || modifiedTimeNano < 0 || modifiedTimeNano > 999_999_999) {
            throw new IllegalArgumentException("Resolved file IDs, revisions, size, or mtime are invalid");
        }
        Objects.requireNonNull(locationContextId, "LocationContext ID");
        if (!LocationKeyCodec.matches(fileLocationPath, fileLocationKey)
                || relativePath == null || relativePath.isEmpty()
                || !relativePath.equals(relativePathKey)
                || !MacOsApfsLocationContextEvidence.FILE_SYSTEM_TYPE.equals(fileSystemType)
                || volumeUuid == null
                || childStorageBoundary != ChildStorageBoundary.SAME_ACCEPTED_VOLUME
                || !regularFile || symbolicLinkBoundary) {
            throw new IllegalArgumentException("Resolved file requires coherent trusted APFS observation fields");
        }
    }
}
