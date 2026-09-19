package io.github.topher6835.mediacompare.location;

import java.util.Objects;

/** Typed v1 baseline/observation for a tested local macOS APFS context anchor. */
public record MacOsApfsLocationContextEvidence(
        int version,
        String profile,
        int profileVersion,
        LocationPath anchorLocationPath,
        LocationKey anchorLocationKey,
        String fileSystemType,
        String volumeUuid,
        String anchorInode,
        boolean directory,
        boolean symbolicLink,
        long acceptedAtMs,
        Diagnostics diagnostics) {

    public static final int VERSION = 1;
    public static final String PROFILE = "macos-local-apfs";
    public static final int PROFILE_VERSION = 1;
    public static final String FILE_SYSTEM_TYPE = "apfs";

    public MacOsApfsLocationContextEvidence {
        if (version != VERSION || !PROFILE.equals(profile) || profileVersion != PROFILE_VERSION) {
            throw new IllegalArgumentException("Unsupported macOS APFS context evidence profile");
        }
        EvidenceValues.requirePathAndKey(anchorLocationPath, anchorLocationKey, "Context anchor location");
        if (!FILE_SYSTEM_TYPE.equals(fileSystemType)) {
            throw new IllegalArgumentException("Context evidence must identify APFS");
        }
        volumeUuid = EvidenceValues.canonicalUuid(volumeUuid, "APFS Volume UUID");
        anchorInode = EvidenceValues.unsignedDecimal(anchorInode, false, "Anchor inode");
        acceptedAtMs = EvidenceValues.nonnegative(acceptedAtMs, "Context acceptance timestamp");
        diagnostics = Objects.requireNonNull(diagnostics, "Context diagnostics are required");
    }

    public record Diagnostics(String unixDevice, String fileStoreName, String providerClass) {
        public Diagnostics {
            if (unixDevice != null) {
                unixDevice = EvidenceValues.unsignedDecimal(unixDevice, true, "Unix device diagnostic");
            }
            fileStoreName = EvidenceValues.optionalDiagnostic(fileStoreName, "FileStore name diagnostic");
            providerClass = EvidenceValues.optionalDiagnostic(providerClass, "Provider class diagnostic");
        }

        public static Diagnostics empty() {
            return new Diagnostics(null, null, null);
        }
    }
}
