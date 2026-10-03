package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Owned protected drive-to-Source chain; validation performs fresh native/NIO reconciliation. */
public interface ExfatRetainedRoot extends AutoCloseable {
    LocationPath resolvedRoot();
    WindowsExfatVolumeEvidence volumeEvidence();
    int directoryCount();
    /** Pure nonblocking state check; must never probe native or filesystem state. */
    boolean available();
    /** Transfer to one exact runtime object once; a retained chain cannot be installed in another window/runtime. */
    void claimOwnership(Object runtimeOwner);
    void revalidate() throws IOException;
    @Override void close() throws IOException;
}
