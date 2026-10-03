package io.github.topher6835.mediacompare.scan.authority;

import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityScope;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry.WindowId;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec;

/** Exact admitted runtime authority. Durable revision equality cannot replace this window. */
public record WindowsExfatScanAuthority(ExfatAuthorityScope scope, WindowId window,
        String bundleUuid, long scanRunId, long jobId, long scanRunSourceId, long generation) {
    public WindowsExfatScanAuthority {
        if (scope == null || window == null || window.sourceId() != scope.source().id()
                || bundleUuid == null || !bundleUuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                || scanRunId <= 0 || jobId <= 0 || scanRunSourceId <= 0 || generation <= 0) {
            throw new IllegalArgumentException("Invalid exFAT scan authority");
        }
    }
    public LocationPath root() {
        return new LocationPathCodec().decode(new WindowsExfatEvidenceCodec()
                .decodeSource(scope.source().bindingEvidenceJson()).resolvedRootLocationPath());
    }
    public MissingClaimAuthority missingClaim() {
        return new MissingClaimAuthority(scope.source().id(), scope.source().locationRevision(),
                scope.context().id(), scope.context().revision(), root(), this);
    }
}
