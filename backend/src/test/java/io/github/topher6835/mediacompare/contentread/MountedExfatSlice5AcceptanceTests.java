package io.github.topher6835.mediacompare.contentread;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.matching.*;
import io.github.topher6835.mediacompare.preview.*;
import io.github.topher6835.mediacompare.scan.*;
import io.github.topher6835.mediacompare.session.SessionSourceBoundary;
import io.github.topher6835.mediacompare.web.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit opt-in only. Refuses existing fixture roots. Normal remount is a manual checkpoint. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "media-compare.slice5.mounted", matches = "true")
class MountedExfatSlice5AcceptanceTests {
    @TempDir Path session;
    private MountedSlice5Fixture fixture;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private ExfatAuthorityWindowRegistry registry;
    private ExfatAuthorityScope scope;
    private TracingHost trace;
    private ExfatContentReadCapture oldCapture;

    @Test void mountedQualification() throws Exception {
        fixture = new MountedSlice5Fixture();
        try {
            fixture.create(); fixture.nativeQualification();
            assertEquals("NTFS", Files.getFileStore(session).type().toUpperCase(Locale.ROOT));
            app = new SpringApplicationBuilder(MediaCompareApplication.class, Configuration.class, MountedSlice5ScanConfiguration.class)
                    .initializers(context -> context.getBeanFactory().registerSingleton("mountedFixture", fixture))
                    .web(WebApplicationType.NONE).run("--media-compare.test-catalog-override=true",
                            "--spring.datasource.url=jdbc:sqlite:" + session.resolve("catalog.db") + "?foreign_keys=on&busy_timeout=5000",
                            "--media-compare.preview-cache-root=" + session.resolve("previews"),
                            "--logging.level.root=ERROR", "--logging.level.org.springframework=ERROR",
                            "--logging.level.io.github.topher6835=ERROR", "--logging.level.org.springframework.jdbc.core=ERROR",
                            "--spring.main.banner-mode=off");
            jdbc = bean(JdbcTemplate.class); registry = bean(ExfatAuthorityWindowRegistry.class); trace = bean(TracingHost.class);
            seedSource();
            var scan = bean(IndexingRunAcceptance.class).accept(UUID.randomUUID().toString(), List.of(1L), List.of(new SourceAuthorityWindowRequest(1L, registry.capturePrepared(scope).windowId())));
            bean(Version3ScanExecutionService.class).run(scan.job().scanRunId());
            assertEquals(4, count("file_entry")); assertEquals(4, count("content_record"));
            System.out.println("MOUNTED SCAN PASS four real protected observations/receipts/SHA artifacts");
            var before = durable();
            metadata();
            for (String name : List.of("fixture.png", "fixture.jpeg")) preview(name);
            rollbackAndNoClobber();
            releaseInFlight();
            assertEquals(before, durable());
            System.out.println("DURABLE PASS Source/context/binding-period unchanged through routine reacquisition");
            manualRemount();
            assertEquals(before, durable());
            assertFalse(WindowsExfatSupport.PRODUCTION.available());
            System.out.println("MOUNTED SLICE5 AUTOMATED CHECKS PASS; production exFAT disabled");
        } finally {
            if (app != null) app.close();
            fixture.close();
        }
    }
    private <T> T bean(Class<T> type) { return app.getBean(type); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private List<?> durable() {
        return List.of(jdbc.queryForList("SELECT * FROM source"), jdbc.queryForList("SELECT * FROM location_context"),
                jdbc.queryForList("SELECT * FROM source_binding_period"));
    }
    private void seedSource() throws Exception {
        try (var chain = fixture.host.openDirectoryChain(fixture.root)) {
            scope = scope(1, CONTEXT, 1, 1, chain.resolvedRoot(), chain.volumeEvidence());
        }
        bean(LocationContextRepository.class).insert(scope.context());
        assertEquals(1, bean(CatalogRepository.class).insert(scope.source()).id());
        var period = bean(SourceBindingPeriodRepository.class).insertOpen(scope.period());
        scope = new ExfatAuthorityScope(scope.source(), scope.context(), period); prepare();
    }
    private ExfatAuthorityWindowRegistry.WindowId prepare() throws Exception {
        var chain = fixture.host.openDirectoryChain(fixture.root);
        try (var attempt = registry.begin(1, chain.directoryCount(), CONTEXT)) {
            return registry.installing(attempt, () -> registry.install(attempt, scope, chain));
        } catch (Exception failure) { chain.close(); throw failure; }
    }
    private ExfatContentReadBundles.Batch batch() throws Exception {
        var window = prepare();
        return bean(ExfatContentReadBundles.class).admitBatch(List.of(new SourceAuthorityWindowRequest(1, window.windowId())));
    }
    private long fileId(String name) {
        return jdbc.queryForObject("SELECT id FROM file_entry WHERE location_path=?", Long.class, lp(path(fixture.root.resolve(name).toString())));
    }
    private ExfatContentReadCapture capture(ExfatContentReadBundles.Batch batch, String name) {
        var candidate = bean(MediaMetadataCandidateRepository.class).findExfatOccurrence(fileId(name), List.of(1L)).orElseThrow();
        return bean(ExfatContentReadCatalog.class).capture(batch.owners().getFirst(), candidate.fileEntryId(), candidate.membershipId());
    }
    private void drained(ExfatContentReadBundles.Batch batch, ExfatContentReadCapture capture) throws Exception {
        assertTrue(batch.drained()); assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED,
                registry.release(capture.authority().window()).releaseState()); assertEquals(0, fixture.calls.live());
    }
    private void metadata() throws Exception {
        var window = prepare();
        var job = bean(MediaMetadataJobService.class).create(new CreateMediaMetadataRunRequest(null,
                List.of(new SourceAuthorityWindowRequest(1, window.windowId()))));
        assertEquals("COMPLETED", bean(MediaMetadataJobService.class).run(job.details().job().id()).job().status());
        registry.release(window); assertEquals(0, fixture.calls.live());
        for (String name : List.of("fixture.png", "fixture.jpeg", "nested/child.png")) {
            var file = bean(CatalogRepository.class).findFileEntryById(fileId(name)).orElseThrow();
            var result = (AvailableMediaMetadata) bean(MediaMetadataCache.class)
                    .findReusableResult(file.currentContentId(), ImageIoMediaMetadataDefinition.definition()).orElseThrow();
            assertEquals(43, result.image().width()); assertEquals(27, result.image().height());
            System.out.println("METADATA PASS " + name + " encoded=43x27");
        }
        var unsupported = bean(CatalogRepository.class).findFileEntryById(fileId("unsupported.txt")).orElseThrow();
        assertEquals(MediaMetadataOutcome.UNSUPPORTED, bean(MediaMetadataCache.class)
                .findReusableResult(unsupported.currentContentId(), ImageIoMediaMetadataDefinition.definition()).orElseThrow().outcome());
        System.out.println("METADATA PASS unsupported file attributed without broader decoder support");
    }
    private void preview(String name) throws Exception {
        var batch = batch(); var capture = capture(batch, name); var item = batch.admitItem(); batch.seal();
        ThumbnailGenerationResult result;
        try (item) { result = bean(ExfatThumbnailService.class).generate(capture); }
        assertEquals(ThumbnailGenerationResult.Outcome.GENERATED, result.outcome());
        assertEquals(name.endsWith("jpeg") ? 27 : 43, result.asset().pixelWidth());
        assertEquals(name.endsWith("jpeg") ? 43 : 27, result.asset().pixelHeight());
        drained(batch, capture); int opens = fixture.calls.fileOpens;
        assertEquals(ThumbnailGenerationResult.Outcome.REUSED, bean(SmallThumbnailService.class).generate(fileId(name)).outcome());
        assertEquals(opens, fixture.calls.fileOpens);
        assertEquals(RevealFileService.Result.STALE_AUTHORITY, bean(RevealFileService.class).reveal(fileId(name)));
        var physical = bean(RevealCatalog.class).capture(fileId(name));
        assertEquals(CleanupPreflightReason.AUTHORITY_UNAVAILABLE,
                bean(CleanupPreflightFileValidator.class).validate(physical, capture.sha().digestHex()));
        Path cached = session.resolve("previews").resolve(result.asset().relativePath());
        Files.delete(cached); assertTrue(bean(ExfatThumbnailService.class).cached(fileId(name)).isEmpty());
        var repair = batch(); var fresh = capture(repair, name); var repairItem = repair.admitItem(); repair.seal();
        try (repairItem) { assertEquals(result.asset(), bean(ExfatThumbnailService.class).generate(fresh).asset()); }
        assertTrue(Files.isRegularFile(cached)); drained(repair, fresh);
        System.out.println("PREVIEW PASS " + name + " dimensions=" + result.asset().pixelWidth() + "x" + result.asset().pixelHeight()
                + " commit-before-close/cache-without-window/repair/finite-drain/physical-denial");
    }
    private void rollbackAndNoClobber() throws Exception {
        // Use the nested supported original so no prior preview row can mask publication.
        var batch = batch(); var capture = capture(batch, "nested/child.png"); var item = batch.admitItem(); batch.seal();
        var file = capture.file(); var definition = SmallThumbnailDefinition.definition();
        var evidence = new PreviewSourceEvidence(file.id(), capture.content().id(), file.observationRevision(),
                file.sizeBytes(), file.modifiedTimeEpochSecond(), file.modifiedTimeNano());
        String key = PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL, definition);
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png");
        var cache = bean(PreviewCacheWriter.class); Path temporary = cache.createTemporary(relative, key);
        record Prepared(PreviewAsset asset, PreviewCacheWriter.PreparedPublication output) {}
        long rows = count("preview_asset");
        try (item) {
            var failure = assertThrows(IllegalStateException.class, () -> bean(ExfatProtectedOriginalAccess.class).read(capture,
                    input -> {
                        var size = bean(SmallThumbnailRenderer.class).render(input, temporary).orElseThrow();
                        long bytes = cache.validateTemporary(temporary, size);
                        var asset = new PreviewAsset(null, key, evidence, PreviewKind.SMALL_THUMBNAIL, definition, relative,
                                "image/png", size.width(), size.height(), bytes, System.currentTimeMillis());
                        return new Prepared(asset, cache.preparePublication(temporary, asset));
                    }, (prepared, proof) -> {
                        bean(ThumbnailPublisher.class).publishProtected(capture, proof, prepared.asset(), prepared.output());
                        throw new IllegalStateException("Mounted acceptance post-install rollback");
                    }));
            assertEquals("Mounted acceptance post-install rollback", failure.getMessage());
        } finally { Files.deleteIfExists(temporary); }
        assertEquals(rows, count("preview_asset"));
        Path output = session.resolve("previews").resolve(relative); assertTrue(Files.isRegularFile(output)); drained(batch, capture);
        // Corrupt immutable occupied output must be preserved, with neither a row nor clobber.
        byte[] occupied = new byte[] {1,2,3,4}; Files.write(output, occupied);
        var blocked = batch(); var fresh = capture(blocked, "nested/child.png"); var blockedItem = blocked.admitItem(); blocked.seal();
        try (blockedItem) { assertThrows(RuntimeException.class, () -> bean(ExfatThumbnailService.class).generate(fresh)); }
        assertArrayEquals(occupied, Files.readAllBytes(output)); assertEquals(rows, count("preview_asset")); drained(blocked, fresh);
        System.out.println("CACHE PASS guarded rollback/orphan/no-clobber/failure ownership drain");
    }
    private void releaseInFlight() throws Exception {
        var batch = batch(); var captured = capture(batch, "fixture.png"); oldCapture = captured;
        var item = batch.admitItem(); batch.seal(); var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = pool.submit(() -> {
                try (item) {
                    assertThrows(Exception.class, () -> bean(ExfatProtectedOriginalAccess.class).read(captured,
                            input -> { entered.countDown(); awaitUninterruptibly(finish); return 1; },
                            (decoded, proof) -> { fail("Released read published"); return null; }));
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING, registry.release(captured.authority().window()).releaseState());
            assertTrue(fixture.calls.live() > 0); assertFalse(batch.drained());
            finish.countDown(); read.get(10, TimeUnit.SECONDS); drained(batch, captured);
            var newer = prepare(); int opens = fixture.calls.fileOpens;
            assertThrows(IllegalStateException.class, () -> bean(ExfatProtectedOriginalAccess.class).read(captured, input -> null, (a,b) -> null));
            assertEquals(opens, fixture.calls.fileOpens); registry.release(newer); assertEquals(0, fixture.calls.live());
            assertTrue(bean(ExfatThumbnailService.class).cached(fileId("fixture.png")).isPresent());
            System.out.println("RELEASE PASS in-flight drain/old-capture rejection/new-window cannot be borrowed/cache survives");
        } finally { finish.countDown(); pool.shutdownNow(); }
    }
    private void manualRemount() throws Exception {
        if (!Boolean.getBoolean("media-compare.slice5.manual-remount")) {
            System.out.println("MANUAL REMOUNT PENDING: no normal dismount/remount performed by this invocation"); return;
        }
        Path control = Path.of(System.getProperty("media-compare.slice5.control")).toAbsolutePath();
        Files.createDirectory(control);
        var batch = batch(); var capture = capture(batch, "fixture.jpeg"); var item = batch.admitItem(); batch.seal();
        // Keep the actual protected original open until the operator finishes the normal blocked attempt.
        try (item) {
            bean(ExfatProtectedOriginalAccess.class).read(capture, input -> {
                Files.writeString(control.resolve("state.txt"), "HELD");
                awaitControl(control.resolve("release.txt")); return null;
            }, (a,b) -> null);
        }
        drained(batch, capture); Files.writeString(control.resolve("state.txt"), "RELEASED");
        awaitControl(control.resolve("remounted.txt")); fixture.preflight();
        int opens = fixture.calls.fileOpens;
        assertThrows(IllegalStateException.class, () -> bean(ExfatProtectedOriginalAccess.class).read(oldCapture, a -> null, (a,b) -> null));
        assertThrows(IllegalStateException.class, () -> bean(ExfatProtectedOriginalAccess.class).read(capture, a -> null, (a,b) -> null));
        assertEquals(opens, fixture.calls.fileOpens);
        var freshBatch = batch(); var fresh = capture(freshBatch, "fixture.jpeg"); var freshItem = freshBatch.admitItem(); freshBatch.seal();
        assertNotEquals(capture.authority().window(), fresh.authority().window());
        try (freshItem) {
            bean(ExfatProtectedOriginalAccess.class).read(fresh, input -> {
                var reader = JdkOriginalImageReaders.select(input).orElseThrow();
                try { reader.setInput(input); assertEquals(43, reader.read(0).getWidth()); } finally { reader.dispose(); } return null;
            }, (a,b) -> null);
        }
        drained(freshBatch, fresh); Files.writeString(control.resolve("state.txt"), "COMPLETE");
        System.out.println("REMOUNT PASS old captures rejected/fresh protected JPEG acquired/durable comparison follows");
    }
    private static void awaitControl(Path signal) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        while (!Files.exists(signal)) {
            if (System.nanoTime() > deadline) throw new IOException("Manual checkpoint timed out: " + signal);
            try { Thread.sleep(250); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        }
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try { assertTrue(latch.await(30, TimeUnit.SECONDS)); return; }
                catch (InterruptedException failure) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary ExfatAuthorityWindowRegistry mountedRegistry(io.github.topher6835.mediacompare.config.CatalogOwnership ownership) { return shortDrainRegistry(ownership); }
        @Bean TracingHost mountedHost(MountedSlice5Fixture fixture) { return new TracingHost(fixture); }
        @Bean @Primary ExfatContentReadBundles mountedContent(ExfatAuthorityWindowRegistry registry, ExfatContentReadCatalog catalog,
                ExfatScanBundles scans, JobRepository jobs, PlatformTransactionManager manager) {
            return new ExfatContentReadBundles(registry, catalog, scans, jobs, manager, true);
        }
        @Bean @Primary ExfatProtectedOriginalAccess mountedOriginal(ExfatAuthorityWindowRegistry registry, ExfatContentReadCatalog catalog,
                SessionSourceBoundary boundary, PlatformTransactionManager manager, TracingHost trace) {
            var observed = new PlatformTransactionManager() {
                @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
                @Override public void commit(TransactionStatus status) {
                    trace.commit("publication"); manager.commit(status); trace.commit("commit");
                }
                @Override public void rollback(TransactionStatus status) { manager.rollback(status); }
            };
            return new ExfatProtectedOriginalAccess(registry, catalog, boundary, observed, () -> trace);
        }
        @Bean @Primary ImageMetadataExtractor mountedExtractor() {
            return new ImageIoImageMetadataExtractor() {
                @Override public MediaMetadataResult extract(Path path) { throw new AssertionError("Original metadata pathname reopen"); }
            };
        }
        @Bean @Primary SmallThumbnailRenderer mountedRenderer() {
            return new SmallThumbnailRenderer() {
                @Override public Optional<Dimensions> render(Path path, Path output) { throw new AssertionError("Original thumbnail pathname reopen"); }
            };
        }
    }
    /** Delegates every original IO to the production host/channel, recording hashes and lifetime only. */
    static final class TracingHost implements HostFileSystem {
        private final MountedSlice5Fixture fixture;
        private Trace current;
        TracingHost(MountedSlice5Fixture fixture) { this.fixture = fixture; }
        @Override public String pathText(LocationPath location) { return fixture.host.pathText(location); }
        @Override public boolean unsafeElement(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
            throw new AssertionError("Protected original pathname inspection overload");
        }
        @Override public ProtectedOriginal openExfatOriginal(ExfatContentReadCapture capture, WindowsExfatNativeAccess.Checkpoint checkpoint) throws IOException {
            int opens = fixture.calls.fileOpens;
            var original = fixture.host.openExfatOriginal(capture, checkpoint);
            assertEquals(opens + 1, fixture.calls.fileOpens, "Exactly one protected file acquisition");
            var channel = new Trace(original.channel(), capture); current = channel;
            return new ProtectedOriginal() {
                @Override public SeekableByteChannel channel() { return channel; }
                @Override public void revalidate() throws IOException { original.revalidate(); }
                @Override public void close() throws IOException {
                    assertEquals(opens + 1, fixture.calls.fileOpens, "No decode/hash pathname reopen");
                    original.close(); channel.events.add("close");
                    assertFalse(channel.isOpen());
                    if (channel.events.contains("commit")) assertEquals(List.of("publication", "commit", "close"), channel.events);
                    System.out.println("ORIGINAL TRACE " + capture.file().locationPath() + " fullHashes=" + channel.hashes
                            + " bytesPerFullHash=" + channel.length + " rewinds=" + channel.rewinds + " events=" + channel.events + " fileOpens=1");
                }
            };
        }
        void commit(String event) {
            assertTrue(current.isOpen()); assertTrue(current.hashes.size() >= 2);
            assertEquals(current.expected, current.hashes.getFirst()); assertEquals(current.expected, current.hashes.getLast());
            current.events.add(event);
        }
    }
    static final class Trace implements SeekableByteChannel {
        private final SeekableByteChannel channel;
        private final long length;
        private final String expected;
        private MessageDigest digest;
        private long bytes;
        private boolean sequential = true;
        private int rewinds;
        final List<String> hashes = new ArrayList<>(), events = new ArrayList<>();
        Trace(SeekableByteChannel channel, ExfatContentReadCapture capture) {
            this.channel = channel; length = capture.content().sizeBytes(); expected = capture.sha().digestHex(); reset();
        }
        private void reset() {
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception failure) { throw new IllegalStateException(failure); }
            bytes = 0; sequential = true;
        }
        @Override public int read(ByteBuffer buffer) throws IOException {
            int start = buffer.position(); int read = channel.read(buffer);
            if (read > 0 && sequential) { var copy = buffer.duplicate(); copy.position(start).limit(start + read); digest.update(copy); bytes += read; }
            if (read < 0 && sequential && bytes == length) { hashes.add(HexFormat.of().formatHex(digest.digest())); sequential = false; }
            return read;
        }
        @Override public SeekableByteChannel position(long position) throws IOException {
            channel.position(position); if (position == 0) { reset(); rewinds++; } else if (position != bytes) sequential = false; return this;
        }
        @Override public long position() throws IOException { return channel.position(); }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public boolean isOpen() { return channel.isOpen(); }
        @Override public void close() throws IOException { channel.close(); }
        @Override public int write(ByteBuffer bytes) throws IOException { return channel.write(bytes); }
        @Override public SeekableByteChannel truncate(long size) throws IOException { channel.truncate(size); return this; }
    }
}
