package io.github.topher6835.mediacompare.contentread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.imageio.stream.ImageInputStream;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns one original from first hash through committed result attribution. */
@Component
public class ExfatProtectedOriginalAccess {
    @FunctionalInterface public interface Decoder<T> { T decode(ImageInputStream input) throws IOException; }
    @FunctionalInterface public interface Publication<T, R> { R publish(T decoded, VerifiedRead proof) throws IOException; }
    /** Unforgeable, thread-confined proof of both hashes, valid only inside the guarded publication callback. */
    public static final class VerifiedRead {
        private final ExfatContentReadCapture capture;
        private final Thread thread = Thread.currentThread();
        private boolean active = true;
        private VerifiedRead(ExfatContentReadCapture capture) { this.capture = capture; }
        public void require(ExfatContentReadCapture expected) {
            if (!active || thread != Thread.currentThread() || !capture.equals(expected)
                    || !TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Publication requires this operation's verified content");
            }
        }
    }

    private final ExfatAuthorityWindowRegistry registry;
    private final ExfatContentReadCatalog catalog;
    private final SessionSourceBoundary boundary;
    private final Supplier<HostFileSystem> hosts;
    private final TransactionTemplate transactions;

    @Autowired
    public ExfatProtectedOriginalAccess(ExfatAuthorityWindowRegistry registry, ExfatContentReadCatalog catalog,
            SessionSourceBoundary boundary, PlatformTransactionManager manager) {
        this(registry, catalog, boundary, manager, HostFileSystems::current);
    }
    ExfatProtectedOriginalAccess(ExfatAuthorityWindowRegistry registry, ExfatContentReadCatalog catalog,
            SessionSourceBoundary boundary, PlatformTransactionManager manager, Supplier<HostFileSystem> hosts) {
        this.registry = registry; this.catalog = catalog; this.boundary = boundary; this.hosts = hosts;
        transactions = new TransactionTemplate(manager);
    }

    public <T, R> R read(ExfatContentReadCapture capture, Decoder<T> decoder,
            Publication<T, R> publication) throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Original IO must be outside catalog transactions");
        }
        catalog.require(capture, false);
        var authority = capture.authority();
        try (var operation = registry.retainContent(authority)) {
            WindowsExfatNativeAccess.Checkpoint checkpoint = () -> operation.checkpointContent(authority);
            HostFileSystem host = hosts.get();
            var route = new WindowsExfatEvidenceCodec().decodeSource(authority.scope().source().bindingEvidenceJson());
            boundary.requireFreshSeparate(host.pathText(new LocationPathCodec().decode(route.resolvedRootLocationPath())));
            checkpoint.check();
            operation.revalidate();
            var path = host.path(new LocationPathCodec().decode(capture.file().locationPath()));
            try (var reservation = operation.reserveDirectories(path.getParent().getNameCount() + 1)) {
                HostFileSystem.ProtectedOriginal original;
                try { original = host.openExfatOriginal(capture, checkpoint); }
                catch (IOException | RuntimeException failure) {
                    // Native acquisition reports uncertain partial cleanup as suppressed close failures.
                    if (failure.getSuppressed().length != 0) registry.cleanupFailed();
                    throw failure;
                }
                try {
                    original.revalidate();
                    SeekableByteChannel channel = original.channel();
                    hash(channel, capture, operation, checkpoint);
                    channel.position(0);
                    T decoded;
                    try (var input = new BorrowedImageInput(channel)) { decoded = decoder.decode(input); }
                    // PNG may have flushed its wrapper. Rewind the retained CHANNEL, never that wrapper.
                    channel.position(0);
                    hash(channel, capture, operation, checkpoint);
                    original.revalidate();
                    operation.revalidate();
                    catalog.require(capture, false);
                    try (var gate = registry.requireContent(authority)) {
                        return transactions.execute(status -> {
                            gate.checkpointContent(authority);
                            catalog.require(capture, true);
                            VerifiedRead proof = new VerifiedRead(capture);
                            try {
                                R result = publication.publish(decoded, proof);
                                gate.checkpointContent(authority);
                                return result;
                            } catch (IOException failure) { throw new PublicationFailure(failure); }
                            finally { proof.active = false; }
                        });
                    } catch (PublicationFailure failure) { throw (IOException) failure.getCause(); }
                } finally {
                    try { original.close(); }
                    catch (IOException | RuntimeException failure) { registry.cleanupFailed(); throw failure; }
                }
            }
        }
    }

    private static void hash(SeekableByteChannel channel, ExfatContentReadCapture capture,
            ExfatAuthorityWindowRegistry.Operation operation, WindowsExfatNativeAccess.Checkpoint checkpoint)
            throws IOException {
        if (channel.position() != 0) throw new IOException("Original hash must start at zero");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
        long count = 0;
        while (true) {
            checkpoint.check();
            int read = channel.read(bytes);
            if (read < 0) break;
            if (read == 0) continue;
            count = Math.addExact(count, read);
            if (count > capture.content().sizeBytes()) throw new IOException("Protected content length mismatch");
            bytes.flip(); digest.update(bytes); bytes.clear(); operation.progress();
        }
        checkpoint.check();
        if (count != capture.content().sizeBytes()
                || !HexFormat.of().formatHex(digest.digest()).equals(capture.sha().digestHex())) {
            throw new IOException("Protected content SHA/length mismatch");
        }
    }

    private static final class PublicationFailure extends RuntimeException {
        PublicationFailure(IOException cause) { super(cause); }
    }
}
