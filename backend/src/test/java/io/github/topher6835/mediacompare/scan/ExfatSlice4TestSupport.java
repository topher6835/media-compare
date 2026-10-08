package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.*;
import io.github.topher6835.mediacompare.location.*;
import io.github.topher6835.mediacompare.scan.authority.*;

/** Real SQLite transactions/stage services, deterministic protected-native seam, portable Windows routes. */
abstract class ExfatSlice4TestSupport {
    @TempDir Path directory;
    ConfigurableApplicationContext app;
    JdbcTemplate jdbc;
    ExfatScanBundles bundles;
    ExfatAuthorityWindowRegistry registry;
    Version3ScanExecutionService execution;
    FakeAccess access;
    Hooks hooks;
    final Map<Long, ExfatAuthorityScope> scopes = new HashMap<>();
    final Map<Long, ExfatAuthorityWindowRegistry.WindowId> windows = new HashMap<>();

    @BeforeEach void open() {
        app = new SpringApplicationBuilder(MediaCompareApplication.class, Configuration.class)
                .web(WebApplicationType.NONE).run("--media-compare.test-catalog-override=true",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("catalog.db") + "?foreign_keys=on&busy_timeout=5000",
                        "--logging.level.root=ERROR", "--logging.level.org.springframework=ERROR",
                        "--logging.level.io.github.topher6835=ERROR", "--spring.main.banner-mode=off");
        jdbc = app.getBean(JdbcTemplate.class); bundles = app.getBean(ExfatScanBundles.class);
        registry = app.getBean(ExfatAuthorityWindowRegistry.class); execution = app.getBean(Version3ScanExecutionService.class);
        access = app.getBean(FakeAccess.class); hooks = app.getBean(Hooks.class);
    }
    @AfterEach void close() { if (app != null) app.close(); }

    long source(LocationPath root) {
        long id = scopes.size() + 1L;
        var scope = scope(id, CONTEXT, 1, 1, root);
        var contexts = app.getBean(LocationContextRepository.class);
        if (contexts.findById(CONTEXT).isEmpty()) contexts.insert(scope.context());
        assertEquals(id, app.getBean(CatalogRepository.class).insert(scope.source()).id());
        var period = app.getBean(SourceBindingPeriodRepository.class).insertOpen(scope.period());
        scope = new ExfatAuthorityScope(scope.source(), scope.context(), period);
        scopes.put(id, scope); prepare(id); return id;
    }
    ExfatAuthorityWindowRegistry.WindowId prepare(long id) {
        var scope = scopes.get(id);
        var root = new Root(CurrentLocationAuthority.requirePersisted(scope.source(), scope.context()).root());
        try (var attempt = registry.begin(id, root.directoryCount(), CONTEXT)) {
            var window = registry.installing(attempt, () -> registry.install(attempt, scope, root));
            windows.put(id, window); return window;
        }
    }
    ScanExecutionDetails admit(long... ids) {
        return app.getBean(IndexingRunAcceptance.class).accept(UUID.randomUUID().toString(), Arrays.stream(ids).boxed().toList(), authorityRequests(ids));
    }
    List<io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest> authorityRequests(long... ids) {
        return Arrays.stream(ids).mapToObj(id -> new io.github.topher6835.mediacompare.web.SourceAuthorityWindowRequest(id, windows.get(id).windowId())).toList();
    }
    Map<Long, MissingClaimAuthority> discover(ScanExecutionDetails accepted) {
        return app.getBean(Version3DiscoveryService.class).execute(accepted.job().scanRunId());
    }
    void reconcile(ScanExecutionDetails accepted, Map<Long, MissingClaimAuthority> claims) {
        app.getBean(Version3ReconciliationService.class).execute(accepted.job().scanRunId(), claims);
    }
    void assignment(ScanExecutionDetails accepted) { app.getBean(Version2ContentAssignmentService.class).executeVersion3(accepted.job().scanRunId()); }
    void hashing(ScanExecutionDetails accepted) { app.getBean(Version2ContentHashingService.class).executeVersion3(accepted.job().scanRunId()); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    long active() { return jdbc.queryForObject("SELECT COUNT(*) FROM source_membership WHERE applicability_status='ACTIVE'", Long.class); }
    FileEntry file(long id) { return app.getBean(CatalogRepository.class).findFileEntryById(id).orElseThrow(); }
    FileEntry onlyFile() { return file(jdbc.queryForObject("SELECT MAX(id) FROM file_entry", Long.class)); }
    void stop(ScanExecutionDetails accepted) { app.getBean(Version2InterruptionRecovery.class).failIfActive(accepted.job().id(), "Test interruption"); }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "Barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    static final class Hooks {
        boolean failProgress;
        CountDownLatch committing, finishCommit;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean Hooks slice4Hooks() { return new Hooks(); }
        @Bean @Primary ExfatAuthorityWindowRegistry slice4Registry(CatalogOwnership ownership) { return shortDrainRegistry(ownership); }
        @Bean @Primary ExfatScanBundles slice4Bundles(CatalogRepository catalog, LocationContextRepository contexts,
                SourceBindingPeriodRepository periods, ScanRepository scans, JobRepository jobs,
                ExfatAuthorityWindowRegistry registry, PlatformTransactionManager manager) {
            return new ExfatScanBundles(catalog, contexts, periods, scans, jobs, registry, manager, true);
        }
        @Bean FakeAccess slice4Access(JdbcTemplate jdbc) { return new FakeAccess(jdbc); }
        @Bean @Primary WindowsExfatDiscoveryWalker slice4Walker(ExfatScanBundles bundles,
                ExfatObservationPublicationService publication, ExfatAuthorityWindowRegistry registry, FakeAccess access) {
            return new WindowsExfatDiscoveryWalker(bundles, publication, registry, access);
        }
        @Bean @Primary JobRepository slice4Jobs(JdbcTemplate jdbc, Hooks hooks) {
            return new JobRepository(jdbc) {
                @Override public int updateDiscoveryProgress(long jobId, long stageId, long version, long progress) {
                    int result = super.updateDiscoveryProgress(jobId, stageId, version, progress);
                    if (hooks.failProgress) throw new IllegalStateException("Injected positive rollback");
                    if (hooks.committing != null) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) { hooks.committing.countDown(); await(hooks.finishCommit); }
                        });
                    }
                    return result;
                }
            };
        }
        @Bean @Primary ContentHashFileHasher slice4NoOriginalHasher() {
            return new ContentHashFileHasher() {
                @Override public String hash(ContentHashCandidate candidate) { fail("exFAT original-path hasher invoked"); return null; }
                @Override public String hash(ContentHashCandidate candidate, Runnable progress) { fail("exFAT original-path hasher invoked"); return null; }
            };
        }
    }

    static final class FakeAccess implements WindowsExfatDiscoveryAccess {
        final Map<LocationPath, byte[]> files = new LinkedHashMap<>();
        final Map<LocationPath, Integer> enumerations = new HashMap<>();
        final List<Long> rowsAtFileClose = new ArrayList<>();
        final JdbcTemplate jdbc;
        int opens, closes, evidenceReads;
        long bytes;
        boolean failRead, closeFails, wrongAfter, failEnd, acquisitionCloseFails;
        LocationPath inaccessible;
        Runnable onRead = () -> {}, onEvidence = () -> {};
        FakeAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        void add(String route, String bytes) { files.put(path(route), bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        private void io() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Native IO under writer"); }
        @Override public Directory retainDirectory(LocationPath route) throws IOException {
            io(); if (route.equals(inaccessible)) throw new IOException("Inaccessible subtree");
            if (acquisitionCloseFails) {
                var failure = new IOException("Rejected native acquisition");
                failure.addSuppressed(new IOException("Uncertain acquisition cleanup")); throw failure;
            }
            return new Directory() {
                int checks;
                @Override public List<Entry> enumerate() {
                    io(); enumerations.merge(route, 1, Integer::sum);
                    var children = new LinkedHashMap<String, Entry>();
                    for (var file : files.keySet()) {
                        if (route.contains(file) && route.components().size() < file.components().size()) {
                            String name = file.components().get(route.components().size());
                            children.put(name, new Entry(name, file.components().size() > route.components().size() + 1));
                        }
                    }
                    return List.copyOf(children.values());
                }
                @Override public void revalidate() throws IOException {
                    io(); if (++checks >= 3 && failEnd) throw new IOException("End directory check failed");
                }
                @Override public File openFile(String spelling, WindowsExfatNativeAccess.Checkpoint checkpoint) {
                    io(); opens++; var path = route.append(spelling); byte[] data = files.get(path);
                    return new HeldFile(path, data, checkpoint);
                }
                @Override public void close() { io(); }
            };
        }
        final class HeldFile implements File, SeekableByteChannel {
            final LocationPath path;
            final byte[] data;
            final WindowsExfatNativeAccess.Checkpoint checkpoint;
            boolean open = true;
            int position, observations;
            HeldFile(LocationPath path, byte[] data, WindowsExfatNativeAccess.Checkpoint checkpoint) {
                this.path = path; this.data = data; this.checkpoint = checkpoint;
            }
            @Override public SeekableByteChannel channel() { return this; }
            @Override public ExfatObservationReceipt.ClassificationEvidence evidence() {
                io(); evidenceReads++; onEvidence.run();
                String finalRoute = "\\\\?\\" + new WindowsNtfsHostFileSystem().pathText(path);
                if (++observations == 2 && wrongAfter) finalRoute = "\\\\?\\C:\\Photos\\wrong.jpg";
                return new ExfatObservationReceipt.ClassificationEvidence(32, data.length, 116444741000000000L,
                        true, false, false, false, data.length, 500, 0, "exFAT", finalRoute,
                        "12345678", GUID, "0000000000000001");
            }
            @Override public int read(ByteBuffer buffer) throws IOException {
                io(); checkpoint.check(); onRead.run(); checkpoint.check();
                if (failRead) throw new IOException("Protected hash failed");
                if (position == data.length) return -1;
                int length = Math.min(17, Math.min(buffer.remaining(), data.length - position));
                buffer.put(data, position, length); position += length; bytes += length; return length;
            }
            @Override public long position() { return position; }
            @Override public SeekableByteChannel position(long next) { position = Math.toIntExact(next); return this; }
            @Override public long size() { return data.length; }
            @Override public boolean isOpen() { return open; }
            @Override public int write(ByteBuffer buffer) { throw new UnsupportedOperationException(); }
            @Override public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }
            @Override public void close() throws IOException {
                io(); if (!open) return; open = false; closes++;
                rowsAtFileClose.add(jdbc.queryForObject("SELECT COUNT(*) FROM file_entry", Long.class));
                if (closeFails) throw new IOException("Uncertain CloseHandle after commit");
            }
        }
    }
}
