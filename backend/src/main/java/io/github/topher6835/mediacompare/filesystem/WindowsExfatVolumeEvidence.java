package io.github.topher6835.mediacompare.filesystem;

/** Accepted route signals, never durable physical-medium identity. */
public record WindowsExfatVolumeEvidence(String filesystemProfile, String volumeSerial,
        String volumeGuid, String canonicalDriveRoot) {
    public WindowsExfatVolumeEvidence {
        ExfatReceiptValues.require("EXFAT".equals(filesystemProfile), "Expected exFAT profile");
        ExfatReceiptValues.require(volumeSerial != null && volumeSerial.matches("[0-9a-f]{8}"),
                "Invalid volume serial");
        ExfatReceiptValues.volumeGuid(volumeGuid);
        ExfatReceiptValues.require(canonicalDriveRoot != null && canonicalDriveRoot.matches("[A-Z]:\\\\"),
                "Expected canonical drive root");
    }
}
