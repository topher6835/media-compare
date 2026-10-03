package io.github.topher6835.mediacompare.filesystem;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.location.*;

/** Portable durable fixtures and deterministic retained-authority fakes; never mounted acceptance. */
public final class ExfatSlice3Fixtures {
    public static final String CONTEXT = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    public static final String GUID = "\\\\?\\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\\";
    public static final LocationPath ROOT = path("C:\\Photos");
    public static WindowsExfatSupport enabledForTests() { return new WindowsExfatSupport(true); }
    public static ExfatAuthorityWindowRegistry shortDrainRegistry(io.github.topher6835.mediacompare.config.CatalogOwnership ownership) {
        return new ExfatAuthorityWindowRegistry(ownership, System::nanoTime, java.time.Duration.ofMillis(40));
    }
    public static LocationPath path(String text) { return LocationPathParser.parse(LocationDialect.WINDOWS_DRIVE, text); }
    public static String lp(LocationPath value) { return new LocationPathCodec().encode(value); }
    public static String key(LocationPath value) { return LocationKeyCodec.encode(value).value(); }
    public static WindowsExfatVolumeEvidence volume(LocationPath root) {
        return new WindowsExfatVolumeEvidence("EXFAT", "12345678", GUID, root.rootFields().getFirst() + ":\\");
    }
    public static WindowsExfatContextEvidence contextEvidence() {
        var anchor = path("C:\\");
        return new WindowsExfatContextEvidence(WindowsExfatContextEvidence.VERSION, CONTEXT, 1,
                lp(anchor), key(anchor), volume(ROOT), "EXPLICIT_PREPARE_ACCEPT", 10);
    }
    public static WindowsExfatSourceEvidence sourceEvidence() {
        return new WindowsExfatSourceEvidence(WindowsExfatSourceEvidence.VERSION, 1, 1, CONTEXT, 1,
                "C:\\Photos", key(ROOT), lp(ROOT), key(ROOT), volume(ROOT), true, true, "EXPLICIT_PREPARE_BIND", 10);
    }
    public static ExfatAuthorityScope scope(long sourceId, String contextId, long sourceRevision,
            long contextRevision, LocationPath root) {
        return scope(sourceId, contextId, sourceRevision, contextRevision, root, volume(root));
    }
    public static ExfatAuthorityScope scope(long sourceId, String contextId, long sourceRevision,
            long contextRevision, LocationPath root, WindowsExfatVolumeEvidence volume) {
        var codec = new WindowsExfatEvidenceCodec();
        var anchor = new LocationPath(LocationDialect.WINDOWS_DRIVE, root.rootFields(), List.of());
        var contextEvidence = new WindowsExfatContextEvidence(WindowsExfatContextEvidence.VERSION,
                contextId, contextRevision, lp(anchor), key(anchor), volume, "EXPLICIT_PREPARE_ACCEPT", 10);
        String configured = new WindowsNtfsHostFileSystem().pathText(root);
        var sourceEvidence = new WindowsExfatSourceEvidence(WindowsExfatSourceEvidence.VERSION,
                sourceId, sourceRevision, contextId, contextRevision, configured, key(root), lp(root), key(root),
                volume, true, true, "EXPLICIT_PREPARE_BIND", 10);
        var source = new Source(sourceId, "Pictures", configured, key(root), sourceRevision, "win-drive",
                contextId, codec.encode(sourceEvidence), 0, 10);
        var context = new LocationContext(contextId, lp(anchor), key(anchor), LocationContext.LifecycleStatus.ACTIVE,
                LocationContext.ContinuityStatus.ACCEPTED, contextRevision, codec.encode(contextEvidence), 0, 10);
        var period = new SourceBindingPeriod(sourceId, sourceId, sourceRevision, contextId, "win-drive", configured,
                key(root), source.bindingEvidenceJson(), 10, null, null);
        return new ExfatAuthorityScope(source, context, period);
    }
    public static ExfatAuthorityScope scope() { return scope(1, CONTEXT, 1, 1, ROOT); }

    public static final class Root implements ExfatRetainedRoot {
        private final LocationPath root;
        public final AtomicInteger closes = new AtomicInteger(), validations = new AtomicInteger();
        public volatile boolean available = true, fail, closeFails;
        public WindowsExfatVolumeEvidence evidence;
        private Object runtimeOwner;
        public Root(LocationPath root) { this.root = root; evidence = volume(root); }
        @Override public LocationPath resolvedRoot() { return root; }
        @Override public WindowsExfatVolumeEvidence volumeEvidence() { return evidence; }
        @Override public int directoryCount() { return root.components().size() + 1; }
        @Override public boolean available() { return available; }
        @Override public synchronized void claimOwnership(Object owner) {
            if (!available || owner == null || runtimeOwner != null) throw new IllegalStateException("Root already owned");
            runtimeOwner = owner;
        }
        @Override public void revalidate() throws IOException {
            validations.incrementAndGet();
            if (!available || fail) throw new IOException("Retained authority failed");
        }
        @Override public void close() {
            available = false; closes.incrementAndGet();
            if (closeFails) throw new IllegalStateException("Native close uncertain");
        }
    }
}
