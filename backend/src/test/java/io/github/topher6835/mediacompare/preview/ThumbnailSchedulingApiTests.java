package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.web.MediaLibraryController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ThumbnailSchedulingApiTests {
    CatalogOwnership ownership;
    ThumbnailScheduler scheduler;
    MockMvc mvc;
    CountDownLatch entered;
    CountDownLatch release;
    AtomicInteger calls;

    @BeforeEach
    void create() throws Exception {
        ownership = CatalogOwnership.acquire("jdbc:sqlite::memory:");
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        calls = new AtomicInteger();
        scheduler = new ThumbnailScheduler(ownership, id -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return new ThumbnailGenerationResult(ThumbnailGenerationResult.Outcome.UNSUPPORTED, null);
        });
        mvc = MockMvcBuilders.standaloneSetup(new MediaLibraryController(null, scheduler)).build();
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        scheduler.shutdown(Duration.ofSeconds(10));
        ownership.close();
    }

    @Test
    void acceptsPositiveIdsInRequestOrderAndReturnsWithoutWaitingForGeneration() throws Exception {
        mvc.perform(post("/api/media-library/thumbnails").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileEntryIds\":[19,12,15]}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].fileEntryId").value(19))
                .andExpect(jsonPath("$.results[1].fileEntryId").value(12))
                .andExpect(jsonPath("$.results[2].fileEntryId").value(15))
                .andExpect(jsonPath("$.results[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("QUEUED"))));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals(1, release.getCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"fileEntryIds\":null}", "{\"fileEntryIds\":[]}", "{\"fileEntryIds\":[1,1]}",
            "{\"fileEntryIds\":[0]}", "{\"fileEntryIds\":[-1]}", "{\"fileEntryIds\":[1,0]}", "{\"fileEntryIds\":[null]}", "broken"})
    void rejectsInvalidRequestsBeforeSchedulingAnyId(String json) throws Exception {
        mvc.perform(post("/api/media-library/thumbnails").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, calls.get());
        assertEquals(ThumbnailScheduler.Status.QUEUED, scheduler.schedule(1).status());
    }

    @Test
    void requestSizeLimitRejects101IdsAndAccepts100WithPartialQueueAdmission() throws Exception {
        String ids101 = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));
        mvc.perform(post("/api/media-library/thumbnails").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileEntryIds\":[" + ids101 + "]}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, calls.get());
        scheduler.schedule(1000);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        String ids100 = LongStream.rangeClosed(1, 100).mapToObj(Long::toString).collect(Collectors.joining(","));
        mvc.perform(post("/api/media-library/thumbnails").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileEntryIds\":[" + ids100 + "]}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.results.length()").value(100))
                .andExpect(jsonPath("$.results[63].status").value("QUEUED"))
                .andExpect(jsonPath("$.results[64].status").value("QUEUE_FULL"));
    }

    @Test
    void partiallyFullQueueReturnsPerIdStatusesWithoutRollingBackEarlierAcceptance() throws Exception {
        scheduler.schedule(1);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        for (long id = 2; id <= 64; id++) scheduler.schedule(id);
        mvc.perform(post("/api/media-library/thumbnails").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileEntryIds\":[65,66,1]}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.results[0].fileEntryId").value(65))
                .andExpect(jsonPath("$.results[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.results[1].status").value("QUEUE_FULL"))
                .andExpect(jsonPath("$.results[2].status").value("ALREADY_QUEUED"));
        assertEquals(ThumbnailScheduler.Status.ALREADY_QUEUED, scheduler.schedule(65).status());
    }
}
