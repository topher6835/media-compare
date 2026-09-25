package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import io.github.topher6835.mediacompare.scan.Version2InterruptionRecovery;

/** Startup recovery keeps the durable v3 admission gate reusable. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class Version2StartupTests extends V3ApiTestBase {
    @Autowired Version2InterruptionRecovery recovery;

    @Test
    void interruptedPendingV3ExecutionIsFailedAndNewWorkCanStart() throws Exception {
        var source = source("restart");
        var first = pending(source);
        executor.held.clear();

        recovery.failIfActive(first.job().id(), Version2InterruptionRecovery.RESTART_MESSAGE);

        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, first.job().id()));
        assertEquals(0, count("file_entry"));
        assertEquals(3, pending(source).job().executionVersion());
    }
}
