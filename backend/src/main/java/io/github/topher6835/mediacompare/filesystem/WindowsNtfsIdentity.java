package io.github.topher6835.mediacompare.filesystem;

/** Stable NTFS identity from one handle: volume serial plus 128-bit file ID. */
public record WindowsNtfsIdentity(String volumeSerial, String fileId) {
    public WindowsNtfsIdentity {
        if (volumeSerial == null || !volumeSerial.matches("[0-9a-f]{16}")
                || fileId == null || !fileId.matches("[0-9a-f]{32}")
                || fileId.equals("00000000000000000000000000000000")) {
            throw new IllegalArgumentException("Invalid canonical NTFS identity");
        }
    }
}
