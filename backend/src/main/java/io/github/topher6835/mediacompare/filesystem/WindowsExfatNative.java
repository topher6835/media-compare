package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/** Owned synchronous handles; one lease monitor serializes reads, seeks, observations and close. */
public final class WindowsExfatNative implements WindowsExfatNativeAccess {
    static final int GENERIC_READ = 0x80000000, SHARE_READ = 1, SHARE_WRITE = 2;
    static final int OPEN_EXISTING = 3, BACKUP = 0x02000000, NOFOLLOW = 0x00200000;
    static final int READ_BUFFER_BYTES = 64 * 1024;

    interface NativeHandle { }
    interface Calls {
        NativeHandle open(Path path, int access, int share, int disposition, int flags) throws IOException;
        int handleFlags(NativeHandle handle) throws IOException;
        Observation observe(NativeHandle handle) throws IOException;
        long seek(NativeHandle handle, long position) throws IOException;
        int read(NativeHandle handle, byte[] bytes, int count) throws IOException;
        void close(NativeHandle handle) throws IOException;
    }

    private final Calls calls;
    private final WindowsExfatSupport support;

    public WindowsExfatNative() { this(new WindowsExfatNativeCalls(), WindowsExfatSupport.PRODUCTION); }

    WindowsExfatNative(Calls calls, WindowsExfatSupport support) {
        this.calls = Objects.requireNonNull(calls);
        this.support = Objects.requireNonNull(support);
    }

    @Override
    public Lease openDirectory(Path path) throws IOException {
        Held held = acquire(path, true, () -> { });
        return new Lease() {
            @Override public Observation observe() throws IOException { return held.observe(); }
            @Override public void close() throws IOException { held.close(); }
        };
    }

    @Override
    public FileLease openFile(Path path, Checkpoint checkpoint) throws IOException {
        return acquire(path, false, Objects.requireNonNull(checkpoint));
    }

    private Held acquire(Path path, boolean directory, Checkpoint checkpoint) throws IOException {
        support.requireAvailable();
        checkpoint.check();
        NativeHandle handle = calls.open(path, GENERIC_READ, directory ? SHARE_READ | SHARE_WRITE : SHARE_READ,
                OPEN_EXISTING, NOFOLLOW | (directory ? BACKUP : 0));
        if (handle == null) throw uncertain("Native open returned no owned handle");
        Held held = new Held(handle, checkpoint);
        try {
            if ((calls.handleFlags(handle) & 1) != 0) throw uncertain("Handle is inheritable");
            Observation observation = held.observe();
            if (observation.reparsePoint() || observation.directory() != directory) {
                throw uncertain("Native classification disagrees with requested lease");
            }
            return held;
        } catch (IOException | RuntimeException failure) {
            try { held.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private final class Held implements FileLease {
        private final NativeHandle handle;
        private final Checkpoint checkpoint;
        private ReadChannel channel;
        private boolean closed;
        private IOException closeFailure;

        Held(NativeHandle handle, Checkpoint checkpoint) { this.handle = handle; this.checkpoint = checkpoint; }

        private void requireOpen() throws ClosedChannelException {
            if (closed) throw new ClosedChannelException();
        }

        @Override
        public synchronized Observation observe() throws IOException {
            requireOpen();
            return calls.observe(handle);
        }

        @Override public synchronized SeekableByteChannel channel() {
            if (channel == null) channel = new ReadChannel();
            return channel;
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                // Never retry an uncertain native close or expose its handle for reuse.
                closed = true;
                try { calls.close(handle); } catch (IOException failure) { closeFailure = failure; }
            }
            if (closeFailure != null) throw closeFailure;
        }

        private final class ReadChannel implements SeekableByteChannel {
            private final byte[] buffer = new byte[READ_BUFFER_BYTES];
            private long position;

            @Override
            public int read(ByteBuffer target) throws IOException {
                synchronized (Held.this) {
                    requireOpen();
                    if (target.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
                    if (!target.hasRemaining()) return 0;
                    checkpoint.check();
                    requireOpen();
                    int count = (int) Math.min(Math.min(target.remaining(), buffer.length), Long.MAX_VALUE - position);
                    if (count == 0) throw uncertain("Read offset exceeds signed long range");
                    if (calls.seek(handle, position) != position) throw uncertain("Native read offset disagrees");
                    int read = calls.read(handle, buffer, count);
                    if (read < 0 || read > count) throw uncertain("Native byte count exceeds requested read");
                    if (read == 0) return -1;
                    position = Math.addExact(position, read);
                    target.put(buffer, 0, read);
                    checkpoint.check();
                    return read;
                }
            }

            @Override public long position() throws IOException {
                synchronized (Held.this) { requireOpen(); return position; }
            }

            @Override
            public SeekableByteChannel position(long requested) throws IOException {
                if (requested < 0) throw new IllegalArgumentException("Negative seek offset");
                synchronized (Held.this) {
                    requireOpen();
                    checkpoint.check();
                    requireOpen();
                    if (calls.seek(handle, requested) != requested) throw uncertain("Native seek position disagrees");
                    position = requested;
                    return this;
                }
            }

            @Override public long size() throws IOException { return Held.this.observe().size(); }
            @Override public int write(ByteBuffer bytes) { throw new NonWritableChannelException(); }
            @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
            @Override public boolean isOpen() { synchronized (Held.this) { return !closed; } }
            @Override public void close() throws IOException { Held.this.close(); }
        }
    }

    private static WindowsFileAccessException uncertain(String message) {
        return new WindowsFileAccessException(WindowsFileAccessException.Reason.UNCERTAIN, message);
    }
}
