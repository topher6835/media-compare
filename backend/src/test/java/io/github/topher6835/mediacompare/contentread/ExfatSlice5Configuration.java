package io.github.topher6835.mediacompare.contentread;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.scan.ExfatScanBundles;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit constructor seams only; no production property, JNI, source pathname or mounted volume. */
@TestConfiguration(proxyBeanMethods = false)
public class ExfatSlice5Configuration {
    @Bean public FakeHost slice5Host() { return new FakeHost(); }
    @Bean public PublicationTransactions slice5PublicationTransactions(PlatformTransactionManager manager) {
        return new PublicationTransactions(manager);
    }
    @Bean @Primary public ExfatContentReadBundles slice5Bundles(ExfatAuthorityWindowRegistry registry,
            ExfatContentReadCatalog catalog, ExfatScanBundles scans, JobRepository jobs, PlatformTransactionManager manager) {
        return new ExfatContentReadBundles(registry, catalog, scans, jobs, manager, true);
    }
    @Bean @Primary public ExfatProtectedOriginalAccess slice5Originals(ExfatAuthorityWindowRegistry registry,
            ExfatContentReadCatalog catalog, SessionSourceBoundary boundary, PublicationTransactions transactions, FakeHost host) {
        return new ExfatProtectedOriginalAccess(registry, catalog, boundary, transactions.observed(), () -> host);
    }
    public static ExfatContentReadBundles enabledBundles(ExfatAuthorityWindowRegistry registry,
            ExfatContentReadCatalog catalog, JobRepository jobs, PlatformTransactionManager manager) {
        return new ExfatContentReadBundles(registry, catalog, null, jobs, manager, true);
    }
    /** Observe actual transaction completion, without querying SQLite from original.close(). */
    public static final class PublicationTransactions {
        private final PlatformTransactionManager delegate;
        public Runnable beforeCommit = () -> {}, afterCommit = () -> {};
        PublicationTransactions(PlatformTransactionManager delegate) { this.delegate = delegate; }
        PlatformTransactionManager observed() {
            return new PlatformTransactionManager() {
                @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return delegate.getTransaction(definition);
                }
                @Override public void commit(TransactionStatus status) {
                    assertTrue(status.isNewTransaction());
                    beforeCommit.run();
                    delegate.commit(status);
                    afterCommit.run();
                }
                @Override public void rollback(TransactionStatus status) { delegate.rollback(status); }
            };
        }
    }
    public static final class FakeHost implements HostFileSystem {
        public final Map<String, byte[]> bytes = new HashMap<>();
        public final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        public volatile Held held;
        public Runnable onRead = () -> {}, onClose = () -> {}, onRevalidate = () -> {};
        public boolean closeFails, acquisitionCloseFails;
        @Override public String pathText(LocationPath location) { return new WindowsNtfsHostFileSystem().pathText(location); }
        @Override public Path path(LocationPath location) { return Path.of("/portable/fake/original.png"); }
        @Override public boolean unsafeElement(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
            fail("Native original pathname inspection invoked"); return true;
        }
        @Override public ProtectedOriginal openExfatOriginal(ExfatContentReadCapture capture,
                WindowsExfatNativeAccess.Checkpoint checkpoint) throws IOException {
            io(); opens.incrementAndGet();
            if (acquisitionCloseFails) {
                var failure = new IOException("Native acquisition failed");
                failure.addSuppressed(new IOException("Partial native cleanup uncertain")); throw failure;
            }
            held = new Held(bytes.get(capture.file().locationPath()), checkpoint);
            return held;
        }
        private void io() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Original IO under writer"); }
        public final class Held implements ProtectedOriginal, SeekableByteChannel {
            private final byte[] data;
            private final WindowsExfatNativeAccess.Checkpoint checkpoint;
            public boolean open = true;
            public long bytesRead;
            public int rewinds;
            private int position;
            Held(byte[] data, WindowsExfatNativeAccess.Checkpoint checkpoint) { this.data = data; this.checkpoint = checkpoint; }
            @Override public SeekableByteChannel channel() { return this; }
            @Override public void revalidate() throws IOException { io(); checkpoint.check(); assertTrue(open); onRevalidate.run(); }
            @Override public int read(ByteBuffer target) throws IOException {
                io(); assertTrue(open); checkpoint.check(); onRead.run(); checkpoint.check();
                if (position == data.length) return -1;
                int count = Math.min(97, Math.min(target.remaining(), data.length - position));
                target.put(data, position, count); position += count; bytesRead += count; return count;
            }
            @Override public long position() { return position; }
            @Override public SeekableByteChannel position(long value) throws IOException {
                io(); checkpoint.check(); assertTrue(open);
                position = Math.toIntExact(value); if (value == 0) rewinds++; return this;
            }
            @Override public long size() { return data.length; }
            @Override public boolean isOpen() { return open; }
            @Override public int write(ByteBuffer buffer) { throw new UnsupportedOperationException(); }
            @Override public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }
            @Override public void close() throws IOException {
                io(); if (!open) return; onClose.run(); open = false; closes.incrementAndGet();
                if (closeFails) throw new IOException("Uncertain protected close");
            }
        }
    }
}
