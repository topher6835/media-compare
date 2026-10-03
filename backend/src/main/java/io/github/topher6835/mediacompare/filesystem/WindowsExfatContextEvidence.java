package io.github.topher6835.mediacompare.filesystem;

/** Durable accepted configuration. No runtime UUID, native handle or live flag belongs here. */
public record WindowsExfatContextEvidence(String version, String contextId, long contextRevision,
        String anchorLocationPath, String anchorLocationKey, WindowsExfatVolumeEvidence volume,
        String acceptanceProvenance, long acceptedAtMs) {
    public static final String VERSION = "windows-exfat-context-v1";

    public WindowsExfatContextEvidence {
        ExfatReceiptValues.require(VERSION.equals(version), "Invalid exFAT context version");
        ExfatReceiptValues.uuid(contextId);
        ExfatReceiptValues.positive(contextRevision);
        ExfatReceiptValues.nonnegative(acceptedAtMs);
        var anchor = ExfatReceiptValues.path(anchorLocationPath, anchorLocationKey);
        ExfatReceiptValues.require(volume != null && anchor.components().isEmpty()
                && (anchor.rootFields().getFirst() + ":\\").equals(volume.canonicalDriveRoot()),
                "Context must describe the canonical drive domain");
        ExfatReceiptValues.require("EXPLICIT_PREPARE_ACCEPT".equals(acceptanceProvenance),
                "Invalid acceptance provenance");
    }
}
