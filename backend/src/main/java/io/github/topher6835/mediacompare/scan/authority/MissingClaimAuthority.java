package io.github.topher6835.mediacompare.scan.authority;

import io.github.topher6835.mediacompare.location.LocationPath;

/** Trusted traversal coverage. Only recursive coverage can authorize a missing sweep. */
public record MissingClaimAuthority(long sourceId, long sourceLocationRevision,
        String locationContextId, long locationContextRevision, LocationPath scope,
        WindowsExfatScanAuthority exfat, boolean includeSubfolders) {
    public MissingClaimAuthority(long sourceId, long sourceLocationRevision, String locationContextId,
            long locationContextRevision, LocationPath scope, WindowsExfatScanAuthority exfat) {
        this(sourceId, sourceLocationRevision, locationContextId, locationContextRevision, scope, exfat, true);
    }
    public MissingClaimAuthority withSubfolders(boolean value) {
        return new MissingClaimAuthority(sourceId, sourceLocationRevision, locationContextId,
                locationContextRevision, scope, exfat, value);
    }
    public MissingClaimAuthority(long sourceId, long sourceLocationRevision,
            String locationContextId, long locationContextRevision, LocationPath scope) {
        this(sourceId, sourceLocationRevision, locationContextId, locationContextRevision, scope, null);
    }
}
