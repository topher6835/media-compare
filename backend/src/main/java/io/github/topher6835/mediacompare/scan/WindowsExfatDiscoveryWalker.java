package io.github.topher6835.mediacompare.scan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsHostFileSystem;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.scan.authority.MissingClaimAuthority;
import io.github.topher6835.mediacompare.scan.authority.WindowsExfatScanAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One retained-file observation per traversal route, fanned out only to selected prepared Sources. */
@Component
public class WindowsExfatDiscoveryWalker {
    private final ExfatScanBundles bundles;
    private final ExfatObservationPublicationService publication;
    private final ExfatAuthorityWindowRegistry registry;
    private final WindowsExfatDiscoveryAccess access;

    @Autowired
    public WindowsExfatDiscoveryWalker(ExfatScanBundles bundles, ExfatObservationPublicationService publication,
            ExfatAuthorityWindowRegistry registry) {
        this(bundles, publication, registry, new WindowsExfatNativeDiscoveryAccess());
    }
    WindowsExfatDiscoveryWalker(ExfatScanBundles bundles, ExfatObservationPublicationService publication,
            ExfatAuthorityWindowRegistry registry, WindowsExfatDiscoveryAccess access) {
        this.bundles = bundles; this.publication = publication; this.registry = registry; this.access = access;
    }
    public record Result(long physicalCount, Map<Long, MissingClaimAuthority> claims) { }

    public Result walk(long scanRunId, long initialCount) throws IOException {
        var authorities = bundles.authorities(scanRunId);
        if (authorities.isEmpty()) return new Result(initialCount, Map.of());
        try (var lease = bundles.retain(authorities)) {
            lease.revalidate();
            var maximal = new ArrayList<WindowsExfatScanAuthority>();
            for (var candidate : authorities) {
                boolean contained = false;
                for (var other : authorities) {
                    if (candidate == other || !candidate.scope().context().equals(other.scope().context())) continue;
                    if (sameWindowsRoute(candidate.root(), other.root()) && !candidate.root().equals(other.root())) {
                        throw new IOException("Selected roots have uncertain spelling aliases");
                    }
                    if (other.root().contains(candidate.root()) && (!other.root().equals(candidate.root())
                            || other.scope().source().id() < candidate.scope().source().id())) contained = true;
                }
                if (!contained) maximal.add(candidate);
            }
            long[] count = {initialCount};
            for (var root : maximal) {
                var group = authorities.stream().filter(a -> a.scope().context().equals(root.scope().context())
                        && root.root().contains(a.root())).toList();
                directory(root.root(), group, lease, count);
            }
            lease.revalidate();
            var claims = new HashMap<Long, MissingClaimAuthority>();
            for (var a : authorities) claims.put(a.scope().source().id(), a.missingClaim());
            return new Result(count[0], Map.copyOf(claims));
        } catch (IOException | RuntimeException failure) {
            // Loss of any qualified resource makes this bundle incomplete. Committed positives remain history.
            if (failure.getSuppressed().length != 0) registry.cleanupFailed(); // Rejected acquisition may have an uncertain close.
            authorities.forEach(a -> registry.invalidateVolume(a.scope().context().id()));
            throw failure;
        }
    }

    private void directory(LocationPath path, List<WindowsExfatScanAuthority> sources,
            ExfatScanBundles.Lease lease, long[] count) throws IOException {
        lease.checkpoint();
        if (path.components().size() > 256) throw new IOException("Discovery depth limit");
        try (var reservation = lease.reserveDirectories(path.components().size() + 1)) {
            var directory = access.retainDirectory(path);
            try (var retained = new ProtectedClose(directory)) {
                directory.revalidate();
                List<WindowsExfatDiscoveryAccess.Entry> entries = directory.enumerate();
                lease.progress();
                if (entries.size() > 100_000) throw new IOException("Directory enumeration limit");
                var names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
                for (var entry : entries) {
                    path.append(entry.spelling()); // validates lossless ordinary path components
                    if (!names.add(entry.spelling())) throw new IOException("Ambiguous case aliases in retained directory");
                }
                for (var entry : entries) {
                    lease.checkpoint();
                    var child = path.append(entry.spelling());
                    if (entry.directory()) directory(child, sources, lease, count);
                    else {
                        var participants = sources.stream().filter(a -> a.root().contains(child)).toList();
                        if (!participants.isEmpty()) observe(directory, entry.spelling(), child, participants, lease, count);
                    }
                    lease.progress();
                }
                directory.revalidate();
                lease.checkpoint();
            }
        }
    }

    private void observe(WindowsExfatDiscoveryAccess.Directory directory, String spelling, LocationPath path,
            List<WindowsExfatScanAuthority> participants, ExfatScanBundles.Lease lease, long[] count) throws IOException {
        long started = System.currentTimeMillis();
        var file = directory.openFile(spelling, lease::checkpoint);
        try (var retained = new ProtectedClose(file)) {
            var before = file.evidence();
            requireExactRoute(path, before.finalRoute());
            MessageDigest digest = sha256();
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long bytes = 0;
            var channel = file.channel();
            if (channel.position() != 0) throw new IOException("Protected observation must start at byte zero");
            while (true) {
                lease.checkpoint();
                int read = channel.read(buffer);
                if (read < 0) break;
                bytes = Math.addExact(bytes, read);
                if (read > 0) lease.progress();
                if (bytes > before.nioSizeBytes()) throw new IOException("Protected file exceeded observed length");
                buffer.flip(); digest.update(buffer); buffer.clear();
            }
            lease.checkpoint();
            var after = file.evidence();
            requireExactRoute(path, after.finalRoute());
            directory.revalidate();
            lease.revalidate();
            var observation = new ExfatObservationPublicationService.Observation(path, bytes,
                    HexFormat.of().formatHex(digest.digest()), before, after, started, System.currentTimeMillis());
            long next = Math.incrementExact(count[0]);
            publication.publish(observation, file, participants, next); // returns only after commit, still inside file lifetime
            count[0] = next;
        }
    }

    private final class ProtectedClose implements AutoCloseable {
        private final AutoCloseable resource;
        private ProtectedClose(AutoCloseable resource) { this.resource = resource; }
        @Override public void close() throws IOException {
            try { resource.close(); }
            catch (Exception failure) {
                registry.cleanupFailed(); // No future acquisition can hide an uncertain native close.
                if (failure instanceof IOException io) throw io;
                throw new IOException("Protected discovery close failed", failure);
            }
        }
    }

    private static void requireExactRoute(LocationPath path, String finalRoute) throws IOException {
        if (!finalRoute.startsWith("\\\\?\\") || !LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE,
                finalRoute.substring(4)).equals(path)) throw new IOException("Held exact spelling disagrees with traversal route");
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance(Sha256AnalysisDefinition.ALGORITHM); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Alias candidate comparison only: current unique spelling/ancestry is proven by the held traversal. */
    public static boolean sameWindowsRoute(LocationPath a, LocationPath b) {
        if (a.dialect() != LocationDialect.WINDOWS_DRIVE || b.dialect() != a.dialect()
                || !a.rootFields().equals(b.rootFields()) || a.components().size() != b.components().size()) return false;
        if (HostFileSystems.current().isWindows()) {
            var host = new WindowsNtfsHostFileSystem();
            return java.nio.file.Path.of(host.pathText(a)).equals(java.nio.file.Path.of(host.pathText(b)));
        }
        // Portable fake-handle tests only; a non-Windows host cannot open production exFAT originals.
        for (int i = 0; i < a.components().size(); i++) {
            if (!a.components().get(i).equalsIgnoreCase(b.components().get(i))) return false;
        }
        return true;
    }
}
