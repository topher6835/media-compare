package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.time.Instant;

/** Retained observations only: legacy indices are contradiction evidence, never identity. */
public interface WindowsExfatNativeAccess {
    @FunctionalInterface
    interface Checkpoint { void check() throws IOException; }

    interface Lease extends AutoCloseable {
        Observation observe() throws IOException;
        @Override void close() throws IOException;
    }

    interface FileLease extends Lease {
        /** Closing this channel closes its owning lease; it must not outlive that lease. */
        SeekableByteChannel channel();
    }

    record Observation(int attributes, String volumeSerial, String legacyIndex, long size,
            long creation100ns, long access100ns, long modified100ns, String finalPath) {
        public boolean directory() { return (attributes & 0x10) != 0; }
        public boolean reparsePoint() { return (attributes & 0x400) != 0; }
        public Instant modifiedInstant() {
            long ticks = Math.subtractExact(modified100ns, 116444736000000000L);
            return Instant.ofEpochSecond(Math.floorDiv(ticks, 10_000_000),
                    Math.floorMod(ticks, 10_000_000) * 100);
        }
    }

    Lease openDirectory(Path path) throws IOException;
    FileLease openFile(Path path, Checkpoint checkpoint) throws IOException;
}
