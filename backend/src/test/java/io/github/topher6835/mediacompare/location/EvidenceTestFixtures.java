package io.github.topher6835.mediacompare.location;

final class EvidenceTestFixtures {
    static final String CONTEXT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String VOLUME_UUID = "11111111-2222-3333-4444-555555555555";
    static final LocationPath ANCHOR = LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive");
    static final LocationPath ROOT = LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Photos");

    private EvidenceTestFixtures() {
    }

    static MacOsApfsLocationContextEvidence context() {
        return context(ANCHOR, VOLUME_UUID, "2", true, false, 1_800_000_000_000L,
                new MacOsApfsLocationContextEvidence.Diagnostics(
                        "16777234", "/dev/disk9s1", "sun.nio.fs.MacOSXFileSystemProvider"));
    }

    static MacOsApfsLocationContextEvidence context(
            LocationPath anchor,
            String volumeUuid,
            String inode,
            boolean directory,
            boolean symbolicLink,
            long acceptedAtMs,
            MacOsApfsLocationContextEvidence.Diagnostics diagnostics) {
        return new MacOsApfsLocationContextEvidence(
                MacOsApfsLocationContextEvidence.VERSION,
                MacOsApfsLocationContextEvidence.PROFILE,
                MacOsApfsLocationContextEvidence.PROFILE_VERSION,
                anchor,
                LocationKeyCodec.encode(anchor),
                MacOsApfsLocationContextEvidence.FILE_SYSTEM_TYPE,
                volumeUuid,
                inode,
                directory,
                symbolicLink,
                acceptedAtMs,
                diagnostics);
    }

    static MacOsApfsSourceRootEvidence sourceRoot() {
        return sourceRoot(CONTEXT_ID, 3, 7, ROOT, VOLUME_UUID, "123456",
                new MacOsApfsSourceRootEvidence.BirthTime(1_799_999_900L, 123_456_789),
                true, false, 1_800_000_000_000L);
    }

    static MacOsApfsSourceRootEvidence sourceRoot(
            String contextId,
            long contextRevision,
            long sourceRevision,
            LocationPath root,
            String volumeUuid,
            String inode,
            MacOsApfsSourceRootEvidence.BirthTime birthTime,
            boolean directory,
            boolean symbolicLink,
            long acceptedAtMs) {
        return new MacOsApfsSourceRootEvidence(
                MacOsApfsSourceRootEvidence.VERSION,
                MacOsApfsSourceRootEvidence.PROFILE,
                MacOsApfsSourceRootEvidence.PROFILE_VERSION,
                contextId,
                contextRevision,
                sourceRevision,
                root,
                LocationKeyCodec.encode(root),
                volumeUuid,
                inode,
                birthTime,
                directory,
                symbolicLink,
                acceptedAtMs);
    }

    static MacOsApfsSourceRootComparisonContext comparisonContext() {
        return new MacOsApfsSourceRootComparisonContext(CONTEXT_ID, 3, 7, context());
    }
}
