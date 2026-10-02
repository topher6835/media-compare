package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WindowsExfatNativeTests {
    @Test
    void exactDirectoryAndFileFlagsAndOwnedClosure() throws Exception {
        var calls = new FakeCalls();
        var nativeAccess = access(calls);
        calls.directory = true;
        try (var directory = nativeAccess.openDirectory(Path.of("C:/Photos"))) {
            assertEquals(0x80000000, calls.access);
            assertEquals(3, calls.share);
            assertEquals(3, calls.disposition);
            assertEquals(0x02200000, calls.flags);
            assertTrue(directory.observe().directory());
        }
        calls.directory = false;
        var file = nativeAccess.openFile(Path.of("C:/A"), () -> { });
        assertEquals(1, calls.share);
        assertEquals(0x00200000, calls.flags);
        file.channel().close();
        file.close();
        assertEquals(2, calls.closes);
        assertFalse(file.channel().isOpen());
        assertThrows(ClosedChannelException.class, file::observe);
    }

    @Test
    void inheritanceClassificationAndNativeFailuresCleanUpWithoutMaskingErrors() throws Exception {
        var calls = new FakeCalls();
        calls.handleFlags = 1;
        assertThrows(IOException.class, () -> access(calls).openFile(Path.of("C:/A"), () -> { }));
        assertEquals(1, calls.closes);
        calls.handleFlags = 0;
        calls.attributes = 0x400;
        assertThrows(IOException.class, () -> access(calls).openFile(Path.of("C:/A"), () -> { }));
        calls.attributes = 0;
        calls.directory = true;
        assertThrows(IOException.class, () -> access(calls).openFile(Path.of("C:/A"), () -> { }));
        calls.directory = false;
        calls.observeFailure = new WindowsFileAccessException("GetFileInformationByHandle", 6);
        calls.closeFailure = new WindowsFileAccessException("CloseHandle", 5);
        var failure = assertThrows(WindowsFileAccessException.class, () -> access(calls).openFile(Path.of("C:/A"), () -> { }));
        assertSame(calls.observeFailure, failure);
        assertEquals(6, failure.nativeError());
        assertSame(calls.closeFailure, failure.getSuppressed()[0]);
        calls.observeFailure = null;
        var lease = access(calls).openFile(Path.of("C:/A"), () -> { });
        int before = calls.closes;
        assertSame(calls.closeFailure, assertThrows(IOException.class, lease::close));
        assertSame(calls.closeFailure, assertThrows(IOException.class, lease::close));
        assertEquals(before + 1, calls.closes);
        assertFalse(lease.channel().isOpen());
    }

    @Test
    void boundedStreamingSeekEofLongOffsetsAndCancellation() throws Exception {
        var calls = new FakeCalls();
        int[] checkpoints = {0};
        try (var lease = access(calls).openFile(Path.of("C:/A"), () -> checkpoints[0]++)) {
            var channel = lease.channel();
            ByteBuffer bytes = ByteBuffer.allocate(150000);
            assertEquals(65536, channel.read(bytes));
            assertEquals(65536, channel.read(bytes));
            assertEquals(18928, channel.read(bytes));
            assertEquals(150000, channel.position());
            assertTrue(calls.largestRead <= 65536);
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            channel.position(5_000_000_000L);
            assertEquals(5_000_000_000L, channel.position());
            assertEquals(5_000_000_000L, calls.position);
            channel.position(0);
            calls.eof = true;
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertThrows(IllegalArgumentException.class, () -> channel.position(-1));
            assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            assertThrows(NonWritableChannelException.class, () -> channel.truncate(0));
            calls.eof = false;
            calls.badCount = true;
            assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(2)));
            calls.badCount = false;
            channel.position(Long.MAX_VALUE);
            assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
        }
        assertTrue(checkpoints[0] > 5);
        assertThrows(IOException.class, () -> access(calls).openFile(Path.of("C:/A"), () -> { throw new IOException("cancelled"); }));
    }

    @Test
    void closeCannotRaceAnInUseNativeRead() throws Exception {
        var calls = new FakeCalls();
        calls.readEntered = new CountDownLatch(1);
        calls.finishRead = new CountDownLatch(1);
        var lease = access(calls).openFile(Path.of("C:/A"), () -> { });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var read = executor.submit(() -> lease.channel().read(ByteBuffer.allocate(1)));
            assertTrue(calls.readEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch closeStarted = new CountDownLatch(1);
            var close = executor.submit(() -> { closeStarted.countDown(); lease.close(); return null; });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertEquals(0, calls.closes);
            calls.finishRead.countDown();
            assertEquals(1, read.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.closes);
        } finally { calls.finishRead.countDown(); lease.close(); }
    }

    private static WindowsExfatNative access(FakeCalls calls) {
        return new WindowsExfatNative(calls, new WindowsExfatSupport(true));
    }

    static class FakeCalls implements WindowsExfatNative.Calls {
        private record Handle() implements WindowsExfatNative.NativeHandle { }
        boolean directory, eof, badCount;
        int access, share, disposition, flags, handleFlags, attributes, closes, largestRead;
        long position;
        IOException observeFailure, closeFailure;
        CountDownLatch readEntered, finishRead;
        @Override public WindowsExfatNative.NativeHandle open(Path path, int access, int share, int disposition, int flags) {
            this.access = access; this.share = share; this.disposition = disposition; this.flags = flags;
            return new Handle();
        }
        @Override public int handleFlags(WindowsExfatNative.NativeHandle handle) { return handleFlags; }
        @Override public WindowsExfatNativeAccess.Observation observe(WindowsExfatNative.NativeHandle handle) throws IOException {
            if (observeFailure != null) throw observeFailure;
            return new WindowsExfatNativeAccess.Observation(attributes | (directory ? 0x10 : 0), "000012ab",
                    "0000000000000000", 5_000_000_100L, 0, 0, 116444736000000000L, "\\\\?\\C:\\A");
        }
        @Override public long seek(WindowsExfatNative.NativeHandle handle, long position) { this.position = position; return position; }
        @Override public int read(WindowsExfatNative.NativeHandle handle, byte[] bytes, int count) throws IOException {
            if (readEntered != null) {
                readEntered.countDown();
                try { if (!finishRead.await(5, TimeUnit.SECONDS)) throw new IOException("read timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            }
            largestRead = Math.max(largestRead, count);
            int result = eof ? 0 : badCount ? count + 1 : count;
            position += result;
            return result;
        }
        @Override public void close(WindowsExfatNative.NativeHandle handle) throws IOException {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }
}
