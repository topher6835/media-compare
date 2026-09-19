package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Typed v1 baseline/observation for a Source root within a local macOS APFS context. */
public record MacOsApfsSourceRootEvidence(
        int version,
        String profile,
        int profileVersion,
        String locationContextId,
        long locationContextRevision,
        long sourceLocationRevision,
        LocationPath rootLocationPath,
        LocationKey rootLocationKey,
        String volumeUuid,
        String rootInode,
        BirthTime rootBirthTime,
        boolean directory,
        boolean symbolicLink,
        long acceptedAtMs) {

    public static final int VERSION = 1;
    public static final String PROFILE = "macos-local-apfs-source-root";
    public static final int PROFILE_VERSION = 1;

    public MacOsApfsSourceRootEvidence {
        if (version != VERSION || !PROFILE.equals(profile) || profileVersion != PROFILE_VERSION) {
            throw new IllegalArgumentException("Unsupported macOS APFS Source-root evidence profile");
        }
        locationContextId = EvidenceValues.canonicalUuid(locationContextId, "LocationContext ID");
        locationContextRevision = EvidenceValues.nonnegative(
                locationContextRevision, "LocationContext revision");
        sourceLocationRevision = EvidenceValues.nonnegative(sourceLocationRevision, "Source location revision");
        EvidenceValues.requirePathAndKey(rootLocationPath, rootLocationKey, "Source root location");
        volumeUuid = EvidenceValues.canonicalUuid(volumeUuid, "APFS Volume UUID");
        rootInode = EvidenceValues.unsignedDecimal(rootInode, false, "Source-root inode");
        rootBirthTime = Objects.requireNonNull(rootBirthTime, "Source-root birth time is required");
        acceptedAtMs = EvidenceValues.nonnegative(acceptedAtMs, "Source-root acceptance timestamp");
    }

    public record BirthTime(long epochSecond, int nano) {
        public BirthTime {
            if (nano < 0 || nano > 999_999_999) {
                throw new IllegalArgumentException("Birth-time nanoseconds are outside 0..999999999");
            }
        }
    }
}
