package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import io.github.topher6835.mediacompare.MediaCompareApplication;
import io.github.topher6835.mediacompare.analysis.*;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.contentread.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.library.MediaLibraryRepository;
import io.github.topher6835.mediacompare.matching.*;
import io.github.topher6835.mediacompare.preview.*;
import io.github.topher6835.mediacompare.web.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExfatSlice5FlowTests extends ExfatSlice4TestSupport {
    ExfatSlice5Configuration.FakeHost host;
    ExfatContentReadBundles contents;
    @Override @BeforeEach void open() {
        app = new SpringApplicationBuilder(MediaCompareApplication.class, Configuration.class, ExfatSlice5Configuration.class)
                .web(WebApplicationType.NONE).run("--media-compare.test-catalog-override=true",
                        "--spring.datasource.url=jdbc:sqlite:" + directory.resolve("catalog.db") + "?foreign_keys=on&busy_timeout=5000",
                        "--media-compare.preview-cache-root=" + directory.resolve("previews"),
                        "--logging.level.root=ERROR", "--logging.level.org.springframework=ERROR",
                        "--logging.level.io.github.topher6835=ERROR", "--spring.main.banner-mode=off");
        jdbc = app.getBean(JdbcTemplate.class); bundles = app.getBean(ExfatScanBundles.class);
        registry = app.getBean(ExfatAuthorityWindowRegistry.class); execution = app.getBean(Version3ScanExecutionService.class);
        access = app.getBean(FakeAccess.class); hooks = app.getBean(Hooks.class);
        host = app.getBean(ExfatSlice5Configuration.FakeHost.class); contents = app.getBean(ExfatContentReadBundles.class);
    }
    ScanExecutionDetails imageScan(String format) throws Exception {
        long id = source(ROOT);
        byte[] bytes = image(format);
        access.files.put(ROOT.append("photo." + format), bytes);
        host.bytes.put(lp(ROOT.append("photo." + format)), bytes);
        var accepted = admit(id); execution.run(accepted.job().scanRunId()); return accepted;
    }
    static byte[] image(String format) throws IOException {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(43, 27, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }
    CreateMediaMetadataRunRequest standalone(long id) {
        return new CreateMediaMetadataRunRequest(null, List.of(new SourceAuthorityWindowRequest(id, windows.get(id).windowId())));
    }
    ExfatContentReadBundles.Batch batch() {
        prepare(1); return contents.admitBatch(standalone(1).authorityWindows());
    }
    ExfatContentReadCapture capture(ExfatContentReadBundles.Batch batch) {
        var route = app.getBean(MediaMetadataCandidateRepository.class).findExfatOccurrence(onlyFile().id(), List.of(1L)).orElseThrow();
        return app.getBean(ExfatContentReadCatalog.class).capture(batch.owners().getFirst(), route.fileEntryId(), route.membershipId());
    }

    @ParameterizedTest @ValueSource(strings = {"jpeg", "png"})
    void exactHandoffReplayMetadataCommitAndSourceFreeCache(String format) throws Exception {
        var scan = imageScan(format); FileEntry prior = onlyFile();
        var request = new CreateMediaMetadataRunRequest(scan.job().scanRunId(), null);
        var first = app.getBean(MediaMetadataJobService.class).create(request);
        var replay = app.getBean(MediaMetadataJobService.class).create(request);
        assertTrue(first.submit()); assertFalse(replay.submit()); assertEquals(first.details(), replay.details());
        assertNull(first.details().job().scanRunId());
        host.onClose = () -> assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record WHERE analysis_type='MEDIA_METADATA' AND status='COMPLETED'", Long.class));
        assertEquals("COMPLETED", app.getBean(MediaMetadataJobService.class).run(first.details().job().id()).job().status());
        assertEquals(1, host.opens.get()); assertEquals(1, host.closes.get()); assertFalse(host.held.open);
        assertTrue(host.held.bytesRead >= 2 * prior.sizeBytes()); assertTrue(host.held.rewinds >= 2);
        assertEquals(prior, onlyFile()); assertEquals(1, count("file_entry")); assertEquals(1, count("content_record"));
        var cached = app.getBean(MediaMetadataCache.class).findReusableResult(prior.currentContentId(), ImageIoMediaMetadataDefinition.definition()).orElseThrow();
        assertEquals(43, ((AvailableMediaMetadata) cached).image().width());
        assertEquals(1, host.opens.get()); assertFalse(WindowsExfatSupport.PRODUCTION.available());
        assertThrows(IllegalStateException.class, () -> app.getBean(MediaMetadataJobService.class).create(request));
    }

    @Test void standaloneUsesFreshBundleAndAdmittedOverlappingRouteInsideMin() throws Exception {
        long lower = source(ROOT), higher = source(ROOT.append("Nested"));
        byte[] bytes = image("png"); var path = ROOT.append("Nested").append("photo.png");
        access.files.put(path, bytes); host.bytes.put(lp(path), bytes);
        var scan = admit(lower, higher); execution.run(scan.job().scanRunId());
        prepare(higher);
        var accepted = app.getBean(MediaMetadataJobService.class).create(standalone(higher));
        var owner = contents.metadataOwners(accepted.details().job().id()).getFirst();
        assertNotEquals(OccurrenceProfileValidation.requireExfat(onlyFile()).bundleUuid(), owner.bundleId());
        var route = app.getBean(MediaMetadataCandidateRepository.class).findExfatOccurrence(onlyFile().id(), List.of(higher)).orElseThrow();
        assertEquals(higher, route.sourceId());
        assertTrue(app.getBean(MediaMetadataCandidateRepository.class).findCurrentOccurrence(onlyFile().id()).isEmpty());
        assertEquals("COMPLETED", app.getBean(MediaMetadataJobService.class).run(accepted.details().job().id()).job().status());
        assertNull(accepted.details().job().scanRunId());
    }

    @ParameterizedTest @ValueSource(strings = {"unsupported", "malformed", "wrong-sha", "wrong-length"})
    void contentAttributionSeparatesDecoderResultsFromByteMismatch(String failure) throws Exception {
        long id = source(ROOT); var path = ROOT.append("photo.png");
        byte[] original = failure.equals("unsupported") ? "GIF89a unsupported".getBytes()
                : failure.equals("malformed") ? new byte[] {(byte)137,80,78,71,13,10,26,10,0} : image("png");
        access.files.put(path, original); host.bytes.put(lp(path), original.clone());
        var scan = admit(id); execution.run(scan.job().scanRunId());
        if (failure.equals("wrong-sha")) host.bytes.get(lp(path))[20] ^= 1;
        if (failure.equals("wrong-length")) host.bytes.put(lp(path), Arrays.copyOf(original, original.length - 1));
        var accepted = app.getBean(MediaMetadataJobService.class).create(new CreateMediaMetadataRunRequest(scan.job().scanRunId(), null));
        if (failure.startsWith("wrong")) {
            assertThrows(RuntimeException.class, () -> app.getBean(MediaMetadataJobService.class).run(accepted.details().job().id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record WHERE analysis_type='MEDIA_METADATA'", Long.class));
        } else {
            app.getBean(MediaMetadataJobService.class).run(accepted.details().job().id());
            assertEquals(failure.equals("malformed") ? "FAILED" : "COMPLETED", jdbc.queryForObject("SELECT status FROM analysis_record WHERE analysis_type='MEDIA_METADATA'", String.class));
        }
        assertEquals(1, host.closes.get()); assertEquals(1, count("file_entry"));
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "incomplete", "config", "artifact", "length"})
    void strictShaPrerequisiteRejectsBeforeOpeningOriginal(String damage) throws Exception {
        imageScan("png"); var batch = batch();
        switch (damage) {
            case "absent" -> jdbc.update("DELETE FROM analysis_record WHERE analysis_type='CONTENT_HASH'");
            case "incomplete" -> jdbc.update("UPDATE analysis_record SET status='FAILED'");
            case "config" -> jdbc.update("UPDATE analysis_record SET configuration_json='[]'");
            case "artifact" -> jdbc.update("UPDATE content_hash SET digest_hex='NOT-A-SHA'");
            case "length" -> jdbc.update("UPDATE content_record SET size_bytes=size_bytes+1");
        }
        assertThrows(RuntimeException.class, () -> capture(batch));
        batch.seal(); assertTrue(batch.drained()); assertEquals(0, host.opens.get());
    }

    @Test void sealedBatchWaitsForEveryActualItemExitAndRejectsLaterWindow() throws Exception {
        imageScan("png"); var batch = batch(); var captured = capture(batch);
        var first = batch.admitItem(); var second = batch.admitItem();
        first.close(); assertFalse(batch.drained()); batch.seal(); assertFalse(batch.drained());
        second.close(); second.close(); assertTrue(batch.drained());
        prepare(1);
        assertThrows(IllegalStateException.class, () -> app.getBean(ExfatProtectedOriginalAccess.class).read(captured, input -> null, (decoded, proof) -> null));
        assertEquals(0, host.opens.get());
    }

    @ParameterizedTest @ValueSource(strings = {"first", "decode", "second"})
    void releaseDuringReadNeverPublishesAndAlwaysCloses(String phase) throws Exception {
        imageScan("png"); var batch = batch(); var capture = capture(batch);
        var item = batch.admitItem(); batch.seal();
        var entered = new CountDownLatch(1); var exit = new CountDownLatch(1);
        var decoding = new java.util.concurrent.atomic.AtomicBoolean();
        var decoded = new java.util.concurrent.atomic.AtomicBoolean();
        host.onRead = () -> {
            if (phase.equals("first") && !decoding.get() && !decoded.get() || phase.equals("second") && decoded.get()) {
                entered.countDown(); awaitIgnoringInterrupt(exit);
            }
        };
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = pool.submit(() -> {
                try (item) {
                    assertThrows(RuntimeException.class, () -> app.getBean(ExfatProtectedOriginalAccess.class).read(capture,
                            input -> { decoding.set(true); if (phase.equals("decode")) { entered.countDown(); awaitIgnoringInterrupt(exit); }
                                decoded.set(true); return 1; }, (value, proof) -> { fail("Released work published"); return null; }));
                }
            });
            await(entered); registry.release(capture.authority().window());
            assertFalse(batch.drained()); assertEquals(0, host.closes.get());
            exit.countDown(); task.get(10, TimeUnit.SECONDS);
            assertTrue(batch.drained()); assertEquals(1, host.closes.get()); assertFalse(host.held.open);
        } finally { exit.countDown(); pool.shutdownNow(); }
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { assertTrue(latch.await(10, TimeUnit.SECONDS)); break; }
        catch (InterruptedException failure) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test void protectedPreviewCommitCacheAndPhysicalDenialAreCatalogOnly() throws Exception {
        imageScan("png"); var file = onlyFile(); var batch = batch(); var capture = capture(batch);
        var item = batch.admitItem(); batch.seal();
        var events = new ArrayList<String>();
        var transactions = app.getBean(ExfatSlice5Configuration.PublicationTransactions.class);
        transactions.beforeCommit = () -> {
            assertTrue(host.held.open); assertEquals(0, host.closes.get());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, count("preview_asset")); // Same writer connection, before its real commit.
            events.add("publication");
        };
        transactions.afterCommit = () -> {
            assertTrue(host.held.open); assertEquals(0, host.closes.get());
            events.add("commit");
        };
        host.onClose = () -> events.add("close");
        var preview = app.getBean(ExfatThumbnailService.class).generate(capture); item.close();
        assertEquals(List.of("publication", "commit", "close"), events);
        assertEquals(1, count("preview_asset"));
        assertEquals(ThumbnailGenerationResult.Outcome.GENERATED, preview.outcome());
        assertTrue(batch.drained());
        assertEquals(ThumbnailGenerationResult.Outcome.REUSED, app.getBean(SmallThumbnailService.class).generate(file.id()).outcome());
        assertEquals(1, host.opens.get());
        assertEquals(RevealFileService.Result.STALE_AUTHORITY, app.getBean(RevealFileService.class).reveal(file.id()));
        var physical = app.getBean(RevealCatalog.class).capture(file.id());
        assertEquals(CleanupPreflightReason.AUTHORITY_UNAVAILABLE, app.getBean(CleanupPreflightFileValidator.class).validate(physical, capture.sha().digestHex()));
        var library = app.getBean(MediaLibraryRepository.class).findById(file.id()).orElseThrow();
        assertFalse(library.physicalActionsAvailable()); assertEquals("AUTHORITY_UNAVAILABLE", library.physicalActionsUnavailableReason());
        assertEquals(file, onlyFile());
    }

    @ParameterizedTest @ValueSource(strings = {"file", "content", "membership", "source", "context", "period", "runtime", "window", "bundle"})
    void exactCapturedEvidenceCannotBeReplacedBeforeIo(String changed) throws Exception {
        imageScan("png"); var batch = batch(); var original = capture(batch);
        var owner = original.authority();
        switch (changed) {
            case "file" -> jdbc.update("UPDATE file_entry SET observation_revision=observation_revision+1");
            case "content" -> jdbc.update("UPDATE content_record SET created_at_ms=created_at_ms+1");
            case "membership" -> jdbc.update("UPDATE source_membership SET membership_revision=membership_revision+1");
            case "source" -> jdbc.update("UPDATE source SET updated_at_ms=updated_at_ms+1");
            case "context" -> jdbc.update("UPDATE location_context SET updated_at_ms=updated_at_ms+1");
            case "period" -> jdbc.update("UPDATE source_binding_period SET bound_at_ms=bound_at_ms+1");
            case "runtime", "window", "bundle" -> {
                var id = new ExfatAuthorityWindowRegistry.WindowId(changed.equals("runtime") ? UUID.randomUUID().toString() : owner.window().runtimeId(),
                        owner.window().sourceId(), changed.equals("window") ? UUID.randomUUID().toString() : owner.window().windowId());
                owner = new ExfatContentReadAuthority(owner.scope(), id, changed.equals("bundle") ? UUID.randomUUID().toString() : owner.bundleId(), null, owner.thumbnailBatchId());
            }
        }
        var captured = new ExfatContentReadCapture(owner, original.file(), original.membership(), original.content(), original.shaAnalysis(), original.sha());
        assertThrows(RuntimeException.class, () -> app.getBean(ExfatProtectedOriginalAccess.class).read(captured, input -> 1,
                (result, proof) -> { fail("Changed capture published"); return null; }));
        batch.seal(); assertEquals(0, host.opens.get());
    }

    @Test void releaseWhilePublicationWaitsCannotEnterWriter() throws Exception {
        imageScan("png"); var batch = batch(); var captured = capture(batch); var item = batch.admitItem(); batch.seal();
        var hashed = new CountDownLatch(1);
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        host.onRevalidate = () -> { if (host.held.bytesRead == 2 * captured.content().sizeBytes()) hashed.countDown(); };
        var pool = Executors.newSingleThreadExecutor();
        try {
            registry.transition(() -> {
                var task = pool.submit(() -> {
                    worker.set(Thread.currentThread());
                    try (item) {
                        assertThrows(RuntimeException.class, () -> app.getBean(ExfatProtectedOriginalAccess.class).read(captured,
                                input -> 1, (value, proof) -> { fail("Released publication entered writer"); return null; }));
                    }
                });
                await(hashed);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (worker.get().getState() != Thread.State.WAITING && !task.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(task.isDone()); assertEquals(Thread.State.WAITING, worker.get().getState(), "Worker must actually wait on held publication gate");
                registry.release(captured.authority().window()); return task;
            }).get(10, TimeUnit.SECONDS);
            assertEquals(1, host.closes.get()); assertTrue(batch.drained());
        } finally { pool.shutdownNow(); }
    }

    @Test void releaseDuringPausedCommitAllowsEstablishedCommitAndClosesAfterward() throws Exception {
        imageScan("png"); var batch = batch(); var captured = capture(batch); var item = batch.admitItem(); batch.seal();
        var committing = new CountDownLatch(1); var commit = new CountDownLatch(1);
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var task = pool.submit(() -> {
                try (item) {
                    return app.getBean(ExfatProtectedOriginalAccess.class).read(captured, input -> 1, (value, proof) -> {
                        proof.require(captured);
                        jdbc.update("UPDATE source SET name=name WHERE id=1");
                        TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) { committing.countDown(); awaitIgnoringInterrupt(commit); }
                            @Override public void afterCommit() { committed.set(true); }
                        });
                        return value;
                    });
                } catch (IOException failure) { throw new IllegalStateException(failure); }
            });
            await(committing); registry.release(captured.authority().window());
            assertEquals(0, host.closes.get()); assertFalse(batch.drained()); commit.countDown();
            assertEquals(1, task.get(10, TimeUnit.SECONDS)); assertTrue(committed.get());
            assertEquals(1, host.closes.get()); assertTrue(batch.drained());
        } finally { commit.countDown(); pool.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void metadataRejectionOrNeverStartedShutdownFailsJobAndDrainsExactOwnership(boolean rejection) throws Exception {
        var scan = imageScan("png");
        var executor = app.getBean(MediaMetadataExecutor.class);
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        executor.execute(() -> { entered.countDown(); awaitIgnoringInterrupt(finish); }); await(entered);
        try {
            if (rejection) executor.execute(() -> {});
            var request = new CreateMediaMetadataRunRequest(scan.job().scanRunId(), null);
            if (rejection) assertThrows(MediaMetadataSchedulingException.class,
                    () -> app.getBean(MediaMetadataBackgroundService.class).start(request));
            else {
                var accepted = app.getBean(MediaMetadataBackgroundService.class).start(request);
                assertEquals("PENDING", accepted.job().status());
                ExfatSlice5ExecutorFixture.stop(executor);
            }
            long job = jdbc.queryForObject("SELECT id FROM job WHERE job_type='MEDIA_METADATA'", Long.class);
            assertEquals("FAILED", app.getBean(MediaMetadataJobService.class).find(job).job().status());
            assertTrue(contents.metadataOwners(job).isEmpty());
            assertFalse(registry.project(scopes.get(1L)).liveAuthorityAvailable()); assertEquals(0, host.opens.get());
        } finally { finish.countDown(); }
    }

    @Test void unrelatedMetadataJobConflictsAndReleasedHandoffCannotBorrowNewWindow() throws Exception {
        var scan = imageScan("png"); var jobs = app.getBean(MediaMetadataJobService.class);
        var nativeJob = jobs.create(); var request = new CreateMediaMetadataRunRequest(scan.job().scanRunId(), null);
        assertThrows(MediaMetadataJobConflictException.class, () -> jobs.create(request));
        app.getBean(MediaMetadataInterruptionRecovery.class).failIfActive(nativeJob.job().id(), "Test interruption");
        prepare(1);
        assertThrows(IllegalStateException.class, () -> jobs.create(request));
        assertEquals(2, count("job"));
        var standalone = jobs.create(standalone(1));
        assertTrue(standalone.submit()); assertNull(standalone.details().job().scanRunId());
        app.getBean(MediaMetadataInterruptionRecovery.class).failIfActive(standalone.details().job().id(), "Test interruption");
        contents.finishMetadata(standalone.details().job().id());
    }

    @Test void existingPreviewRepairInstallsOnlyAfterGuardAndRollbackLeavesNoCatalogArtifact() throws Exception {
        imageScan("png"); var firstBatch = batch(); var first = capture(firstBatch); var item = firstBatch.admitItem(); firstBatch.seal();
        var asset = app.getBean(ExfatThumbnailService.class).generate(first).asset(); item.close();
        java.nio.file.Path target = directory.resolve("previews").resolve(asset.relativePath());
        java.nio.file.Files.delete(target);
        assertTrue(app.getBean(ExfatThumbnailService.class).cached(onlyFile().id()).isEmpty());
        assertThrows(ThumbnailGenerationException.class, () -> app.getBean(SmallThumbnailService.class).generate(onlyFile().id()));
        var nextBatch = batch(); var next = capture(nextBatch); var nextItem = nextBatch.admitItem(); nextBatch.seal();
        host.bytes.get(next.file().locationPath())[20] ^= 1;
        assertThrows(ThumbnailGenerationException.class, () -> app.getBean(ExfatThumbnailService.class).generate(next));
        nextItem.close(); assertFalse(java.nio.file.Files.exists(target)); assertEquals(1, count("preview_asset"));
        host.bytes.get(next.file().locationPath())[20] ^= 1;
        var repairBatch = batch(); var repair = capture(repairBatch); var repairItem = repairBatch.admitItem(); repairBatch.seal();
        app.getBean(ExfatThumbnailService.class).generate(repair); repairItem.close();
        assertTrue(java.nio.file.Files.exists(target)); assertEquals(1, count("preview_asset"));
    }

    @Test void uncertainProtectedCloseRetainsCommittedArtifactAndDisablesAcquisition() throws Exception {
        var scan = imageScan("png"); host.closeFails = true;
        var accepted = app.getBean(MediaMetadataJobService.class).create(new CreateMediaMetadataRunRequest(scan.job().scanRunId(), null));
        assertThrows(RuntimeException.class, () -> app.getBean(MediaMetadataJobService.class).run(accepted.details().job().id()));
        assertTrue(app.getBean(MediaMetadataCache.class).findReusableResult(onlyFile().currentContentId(), ImageIoMediaMetadataDefinition.definition()).isPresent());
        assertFalse(registry.project(scopes.get(1L)).liveAuthorityAvailable()); assertEquals(1, host.closes.get());
    }

    @Test void uncertainPartialAcquisitionCleanupAlsoDeniesFurtherAcquisition() throws Exception {
        var scan = imageScan("png"); host.acquisitionCloseFails = true;
        var accepted = app.getBean(MediaMetadataJobService.class).create(new CreateMediaMetadataRunRequest(scan.job().scanRunId(), null));
        assertThrows(RuntimeException.class, () -> app.getBean(MediaMetadataJobService.class).run(accepted.details().job().id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM analysis_record WHERE analysis_type='MEDIA_METADATA'", Long.class));
        assertFalse(registry.project(scopes.get(1L)).liveAuthorityAvailable());
    }

    @Test void productionAdmissionGateCannotBeBypassedByTestPreparedWindows() throws Exception {
        imageScan("png");
        var production = app.getBean("exfatContentReadBundles", ExfatContentReadBundles.class);
        assertThrows(MediaMetadataJobConflictException.class, () -> production.admitMetadata(standalone(1),
                () -> { fail("Disabled production gate created a Job"); return null; }));
        assertThrows(MediaMetadataJobConflictException.class, () -> production.admitBatch(standalone(1).authorityWindows()));
        assertEquals(0, host.opens.get()); assertFalse(WindowsExfatSupport.PRODUCTION.available());
    }

    @Test void previewRollbackLeavesOnlyDisposableOrphanAfterGuardedInstall() throws Exception {
        imageScan("png"); var batch = batch(); var captured = capture(batch); var item = batch.admitItem(); batch.seal();
        var file = captured.file(); var definition = SmallThumbnailDefinition.definition();
        var evidence = new PreviewSourceEvidence(file.id(), captured.content().id(), file.observationRevision(),
                file.sizeBytes(), file.modifiedTimeEpochSecond(), file.modifiedTimeNano());
        String key = PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL, definition);
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png");
        var cache = app.getBean(PreviewCacheWriter.class); var temporary = cache.createTemporary(relative, key);
        record Prepared(PreviewAsset asset, PreviewCacheWriter.PreparedPublication output) { }
        try (item) {
            var failure = assertThrows(IllegalStateException.class, () -> app.getBean(ExfatProtectedOriginalAccess.class).read(captured,
                    input -> {
                        var dimensions = app.getBean(SmallThumbnailRenderer.class).render(input, temporary).orElseThrow();
                        long bytes = cache.validateTemporary(temporary, dimensions);
                        var asset = new PreviewAsset(null, key, evidence, PreviewKind.SMALL_THUMBNAIL, definition, relative,
                                "image/png", dimensions.width(), dimensions.height(), bytes, System.currentTimeMillis());
                        var output = cache.preparePublication(temporary, asset);
                        assertTrue(output.validatedSha256().matches("[0-9a-f]{64}"));
                        return new Prepared(asset, output);
                    }, (prepared, proof) -> {
                        app.getBean(ThumbnailPublisher.class).publishProtected(captured, proof, prepared.asset(), prepared.output());
                        throw new IllegalStateException("Injected post-install SQLite rollback");
                    }));
            assertEquals("Injected post-install SQLite rollback", failure.getMessage());
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
        assertEquals(0, count("preview_asset"));
        assertTrue(java.nio.file.Files.exists(directory.resolve("previews").resolve(relative)), "Disposable orphan remains inaccessible without catalog row");
        assertEquals(1, host.closes.get()); assertTrue(batch.drained());
    }

    @Test void supersessionAfterAdmissionCannotReattributeOldQueuedCapture() throws Exception {
        imageScan("png"); var before = onlyFile(); var batch = batch(); var captured = capture(batch);
        prepare(1); var newer = admit(1); execution.run(newer.job().scanRunId()); var current = onlyFile();
        assertNotEquals(before.id(), current.id()); assertNotEquals(before.currentContentId(), current.currentContentId());
        assertThrows(RuntimeException.class, () -> app.getBean(ExfatProtectedOriginalAccess.class).read(captured,
                input -> 1, (value, proof) -> { fail("Superseded task published"); return null; }));
        batch.seal(); assertTrue(batch.drained()); assertEquals(0, host.opens.get());
        assertEquals(before, file(before.id()));
        assertNotNull(bundles.requireHandoff(newer.job().scanRunId()));
    }

    @Test void directSequenceRetainsSameChannelThroughWriterAndHandlesPngFlush() throws Exception {
        imageScan("png"); var batch = batch(); var capture = capture(batch); var item = batch.admitItem(); batch.seal();
        var before = onlyFile();
        try (item) {
            app.getBean(ExfatProtectedOriginalAccess.class).read(capture, input -> {
                var selected = JdkOriginalImageReaders.select(input).orElseThrow();
                try { selected.setInput(input, false, true); assertEquals(43, selected.read(0).getWidth()); }
                finally { selected.dispose(); }
                input.flushBefore(input.getStreamPosition());
                assertTrue(host.held.open); return 1;
            }, (decoded, proof) -> { assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(host.held.open); assertEquals(0, host.closes.get()); return null; });
        }
        assertFalse(host.held.open); assertEquals(before, onlyFile()); assertTrue(batch.drained());
    }
}
