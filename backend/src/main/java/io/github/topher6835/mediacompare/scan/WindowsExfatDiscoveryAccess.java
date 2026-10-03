package io.github.topher6835.mediacompare.scan;

import java.io.IOException;
import java.util.List;
import io.github.topher6835.mediacompare.filesystem.ExfatObservationReceipt.ClassificationEvidence;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatNativeAccess;
import io.github.topher6835.mediacompare.location.LocationPath;

/** Discovery-only seam. Directory/file resources remain held until the caller closes them. */
public interface WindowsExfatDiscoveryAccess {
    record Entry(String spelling, boolean directory) { }
    interface Directory extends AutoCloseable {
        List<Entry> enumerate() throws IOException;
        void revalidate() throws IOException;
        File openFile(String spelling, WindowsExfatNativeAccess.Checkpoint checkpoint) throws IOException;
        @Override void close() throws IOException;
    }
    interface File extends AutoCloseable {
        java.nio.channels.SeekableByteChannel channel();
        ClassificationEvidence evidence() throws IOException;
        @Override void close() throws IOException;
    }
    Directory retainDirectory(LocationPath path) throws IOException;
}
