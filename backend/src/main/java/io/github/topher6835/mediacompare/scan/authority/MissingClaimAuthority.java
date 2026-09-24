package io.github.topher6835.mediacompare.scan.authority;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Pure authorization for a future membership sweep within one Source scope. */
public record MissingClaimAuthority(long sourceId, long sourceLocationRevision,
        String locationContextId, long locationContextRevision, LocationPath scope) {
}
