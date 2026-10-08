package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.web.*;

/** Real SQLite admission/public projection/release with the existing portable protected-root seam. */
class ExfatSlice6IntegrationTests extends ExfatSlice4TestSupport {
    SourceAuthorityProjection projection() { return app.getBean(SourceAuthorityProjection.class); }
    Source current(long id) { return app.getBean(CatalogRepository.class).findSourceById(id).orElseThrow(); }

    @Test void publicProjectionReleaseAndDelayedOldUuidPreserveConfiguration() throws Exception {
        long id = source(ROOT), other = source(ROOT.append("Nested"));
        var original = current(id); var period = scopes.get(id).period();
        var old = windows.get(id);
        var mvc = MockMvcBuilders.standaloneSetup(app.getBean(SourceController.class)).build();
        mvc.perform(get("/api/sources/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.filesystemProfile").value("EXFAT"))
                .andExpect(jsonPath("$.preparationState").value("READY"))
                .andExpect(jsonPath("$.liveAuthorityWindowId").value(old.windowId()));
        mvc.perform(post("/api/sources/" + id + "/release-authority").contentType(MediaType.APPLICATION_JSON)
                .content("{\"windowId\":\"" + old.windowId() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.releaseState").value("RELEASED"))
                .andExpect(jsonPath("$.source.liveAuthorityAvailable").value(false))
                .andExpect(jsonPath("$.source.preparationState").value("READY"))
                .andExpect(jsonPath("$.otherWindowsOnVolumeRemain").value(true));
        var fresh = prepare(id);
        projection().release(id, old.windowId());
        assertEquals(fresh.windowId(), projection().project(current(id)).liveAuthorityWindowId());
        assertEquals(original, current(id));
        assertEquals(period, app.getBean(SourceBindingPeriodRepository.class).findOpenBySourceId(id).orElseThrow());
        assertTrue(projection().release(id, old.windowId()).otherWindowsOnVolumeRemain());
        projection().release(other, windows.get(other).windowId());
        assertFalse(projection().release(id, fresh.windowId()).otherWindowsOnVolumeRemain());
        assertNull(projection().project(current(id)).liveAuthorityWindowId());
        mvc.perform(get("/api/sources")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void malformedReleaseAndUnknownSourceHaveBoundedHttpResponses() throws Exception {
        long id = source(ROOT);
        var mvc = MockMvcBuilders.standaloneSetup(app.getBean(SourceController.class)).build();
        for (String invalid : List.of("bad", "1-1-1-1-1", windows.get(id).windowId().toUpperCase(Locale.ROOT))) {
            mvc.perform(post("/api/sources/" + id + "/release-authority").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"windowId\":\"" + invalid + "\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/sources/999/release-authority").contentType(MediaType.APPLICATION_JSON)
                .content("{\"windowId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
        assertTrue(projection().project(current(id)).liveAuthorityAvailable());
    }

    @Test void staleCatalogProjectionNeverRepairsOrTouchesRetainedHostRoot() {
        long id = source(ROOT);
        var root = new Root(ROOT);
        registry.release(windows.get(id));
        try (var attempt = registry.begin(id, root.directoryCount(), CONTEXT)) {
            registry.installing(attempt, () -> registry.install(attempt, scopes.get(id), root));
        }
        root.fail = true; // Projection is catalog/registry only, not a native revalidation.
        assertTrue(projection().project(current(id)).liveAuthorityAvailable());
        jdbc.update("UPDATE source SET name='changed' WHERE id=?", id);
        assertFalse(projection().project(current(id)).liveAuthorityAvailable());
        assertTrue(registry.ownsSource(id)); // GET does not repair/revoke stale state.
    }

    @Test void mixedNativeAndExfatAdmissionRequiresOnlyExfatWindows() throws Exception {
        long exfat = source(ROOT);
        var catalog = app.getBean(CatalogRepository.class);
        var nativeSource = V3TestHost.boundSource(catalog, jdbc,
                java.nio.file.Files.createDirectory(directory.resolve("native")), "Native");
        var nativeProjection = projection().project(nativeSource);
        assertEquals(FileSystemProfile.APFS, nativeProjection.filesystemProfile());
        assertNull(nativeProjection.liveAuthorityAvailable()); assertNull(nativeProjection.liveAuthorityWindowId());
        var acceptance = app.getBean(IndexingRunAcceptance.class);
        assertThrows(IllegalArgumentException.class, () -> acceptance.accept(UUID.randomUUID().toString(),
                List.of(exfat, nativeSource.id()), List.of(authorityRequests(exfat).getFirst(),
                    new SourceAuthorityWindowRequest(nativeSource.id(), UUID.randomUUID().toString()))));
        var mixed = acceptance.accept(UUID.randomUUID().toString(), List.of(exfat, nativeSource.id()), authorityRequests(exfat));
        assertEquals(1, bundles.authorities(mixed.job().scanRunId()).size());
        stop(mixed);
        assertNotNull(acceptance.accept(UUID.randomUUID().toString(), List.of(nativeSource.id())).job());
    }

    @Test void admissionRequiresExactSetBelowHttpAndDoesNotCreateJobsOnDenial() {
        long a = source(ROOT), b = source(ROOT.append("Nested"));
        var acceptance = app.getBean(IndexingRunAcceptance.class);
        assertThrows(Version2ExecutionConflictException.class, () -> acceptance.accept(UUID.randomUUID().toString(), List.of(a)));
        assertThrows(Version2ExecutionConflictException.class, () -> acceptance.accept(UUID.randomUUID().toString(), List.of(a), authorityRequests(b)));
        assertThrows(IllegalArgumentException.class, () -> acceptance.accept(UUID.randomUUID().toString(), List.of(a), authorityRequests(a, b)));
        assertThrows(IllegalArgumentException.class, () -> acceptance.accept(UUID.randomUUID().toString(), List.of(a), authorityRequests(a, a)));
        var wrong = List.of(new SourceAuthorityWindowRequest(a, windows.get(b).windowId()));
        assertThrows(Version2ExecutionConflictException.class, () -> acceptance.accept(UUID.randomUUID().toString(), List.of(a), wrong));
        var old = authorityRequests(a); registry.release(windows.get(a)); prepare(a);
        assertThrows(Version2ExecutionConflictException.class, () -> acceptance.accept(UUID.randomUUID().toString(), List.of(a), old));
        assertEquals(0, count("job")); assertEquals(0, count("scan_run"));
        var request = app.getBean(ScanRunService.class).create(List.of(a));
        assertThrows(Version2ExecutionConflictException.class, () -> execution.create(request.scanRun().id()));
        assertEquals(0, count("job"));
    }

    @Test void httpIndexingCarriesExactWindowsAndReplayNeverAttachesReplacement() throws Exception {
        long id = source(ROOT); String key = UUID.randomUUID().toString();
        var first = app.getBean(IndexingRunAcceptance.class).accept(key, List.of(id), authorityRequests(id));
        var captured = bundles.authorities(first.job().scanRunId()).getFirst();
        stop(first); var newer = prepare(id);
        var mvc = MockMvcBuilders.standaloneSetup(app.getBean(IndexingRunController.class)).build();
        mvc.perform(post("/api/indexing-runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestKey\":\"" + key + "\",\"sourceIds\":[" + id
                    + "],\"authorityWindows\":[{\"sourceId\":" + id + ",\"windowId\":\"" + newer.windowId() + "\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scanRunId").value(first.job().scanRunId()));
        assertEquals(newer, registry.capturePrepared(scopes.get(id)));
        assertNotEquals(newer, captured.window());
        assertTrue(bundles.authorities(first.job().scanRunId()).isEmpty());
        assertEquals(1, count("job"));
    }

    @Test void queuedAdmissionCannotBorrowNewWindowAndBackgroundRejectsAbsentBundle() {
        long id = source(ROOT); var accepted = admit(id); var original = windows.get(id);
        registry.release(original); prepare(id);
        assertThrows(RuntimeException.class, () -> execution.run(accepted.job().scanRunId()));
        assertEquals(0, count("file_entry"));
        assertThrows(Version2SchedulingException.class, () -> app.getBean(Version2BackgroundIndexingService.class).submitAccepted(accepted));
        assertEquals("FAILED", app.getBean(JobRepository.class).findJobById(accepted.job().id()).orElseThrow().status());
    }

    @Test void publicReleaseDrainsRetainedUsageBeforeReportingReleased() throws Exception {
        long id = source(ROOT); var accepted = admit(id);
        var authority = bundles.authorities(accepted.job().scanRunId()).getFirst();
        var usage = registry.retainBundle(authority.scope(), authority.window(), authority.bundleUuid(), authority.scanRunId(), authority.jobId());
        try {
            assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.DRAINING,
                    projection().release(id, authority.window().windowId()).releaseState());
            assertFalse(projection().project(current(id)).liveAuthorityAvailable());
        } finally { usage.close(); }
        assertEquals(ExfatAuthorityWindowRegistry.ReleaseState.RELEASED,
                projection().release(id, authority.window().windowId()).releaseState());
        assertEquals(scopes.get(id).source(), current(id));
    }
}
