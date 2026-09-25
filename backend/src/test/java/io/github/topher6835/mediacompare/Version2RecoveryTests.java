package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import io.github.topher6835.mediacompare.scan.Version2InterruptionRecovery;

/** One recovery boundary handles interrupted historical and v3 SCAN Jobs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class Version2RecoveryTests extends V3ApiTestBase {
    @Autowired Version2InterruptionRecovery recovery;

    @Test
    void pendingV3RecoveryIsIdempotentAndPreservesCatalog() throws Exception {
        var source = source("pending-recovery");
        var accepted = pending(source);
        executor.held.clear();

        recovery.failIfActive(accepted.job().id(), Version2InterruptionRecovery.RESTART_MESSAGE);
        recovery.failIfActive(accepted.job().id(), Version2InterruptionRecovery.RESTART_MESSAGE);

        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, accepted.job().id()));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM scan_run WHERE id = ?", String.class, accepted.scanRun().id()));
        assertEquals(0, count("file_entry"));
        assertEquals(0, count("source_membership"));
        assertEquals(3, pending(source).job().executionVersion());
    }

    @Test
    void completedV3JobIsUnaffectedByRecovery() throws Exception {
        var source = source("completed-recovery");
        java.nio.file.Files.writeString(java.nio.file.Path.of(source.rootPath()).resolve("a.txt"), "bytes");
        var accepted = completed(source);
        var before = jdbc.queryForList("SELECT * FROM job WHERE id = ?", accepted.job().id());

        recovery.failIfActive(accepted.job().id(), Version2InterruptionRecovery.RESTART_MESSAGE);

        assertEquals(before, jdbc.queryForList("SELECT * FROM job WHERE id = ?", accepted.job().id()));
        assertEquals(1, count("file_entry"));
    }
}
