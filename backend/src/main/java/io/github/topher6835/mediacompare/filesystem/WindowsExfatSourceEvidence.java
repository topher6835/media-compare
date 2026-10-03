package io.github.topher6835.mediacompare.filesystem;

import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPathParser;

/** Configuration and initial binding provenance; serial/GUID/path equality is not physical identity. */
public record WindowsExfatSourceEvidence(String version, long sourceId, long sourceRevision,
        String contextId, long contextRevision, String configuredRoot, String configuredRootLocationKey,
        String resolvedRootLocationPath, String resolvedRootLocationKey,
        WindowsExfatVolumeEvidence volume, boolean directory, boolean nonLink,
        String bindingProvenance, long boundAtMs) {
    public static final String VERSION = "windows-exfat-source-v1";

    public WindowsExfatSourceEvidence {
        ExfatReceiptValues.require(VERSION.equals(version), "Invalid exFAT Source version");
        ExfatReceiptValues.positive(sourceId, sourceRevision, contextRevision);
        ExfatReceiptValues.uuid(contextId);
        ExfatReceiptValues.nonnegative(boundAtMs);
        ExfatReceiptValues.text(configuredRoot, 64 * 1024);
        var configured = LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, configuredRoot);
        ExfatReceiptValues.require(io.github.topher6835.mediacompare.location.LocationKeyCodec
                .decode(configuredRootLocationKey).equals(configured), "Configured Source key disagrees");
        var resolved = ExfatReceiptValues.path(resolvedRootLocationPath, resolvedRootLocationKey);
        ExfatReceiptValues.require(volume != null && directory && nonLink
                && (resolved.rootFields().getFirst() + ":\\").equals(volume.canonicalDriveRoot())
                && sameSpellingDomain(configured, resolved), "Invalid direct Source route/classification");
        ExfatReceiptValues.require("EXPLICIT_PREPARE_BIND".equals(bindingProvenance),
                "Invalid binding provenance");
    }

    private static boolean sameSpellingDomain(io.github.topher6835.mediacompare.location.LocationPath a,
            io.github.topher6835.mediacompare.location.LocationPath b) {
        if (!a.rootFields().getFirst().equalsIgnoreCase(b.rootFields().getFirst())
                || a.components().size() != b.components().size()) return false;
        for (int i = 0; i < a.components().size(); i++) {
            if (!a.components().get(i).equalsIgnoreCase(b.components().get(i))) return false;
        }
        return true;
    }
}
