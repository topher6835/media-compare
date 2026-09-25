package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.topher6835.mediacompare.scan.ScanRunService;
import io.github.topher6835.mediacompare.scan.Version2BackgroundIndexingService;
import io.github.topher6835.mediacompare.scan.Version2SchedulingException;

/** The background handoff now owns v3 executions. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IndexingRunApiTests.Hooks.class)
class Version2BackgroundIndexingTests extends V3ApiTestBase {
    @Autowired ScanRunService requests;
    @Autowired Version2BackgroundIndexingService background;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void handoffCommitsV3BeforeWorkerRuns() throws Exception {
        var source = source("background");
        java.nio.file.Files.writeString(java.nio.file.Path.of(source.rootPath()).resolve("a.txt"), "bytes");
        long scanId = requests.create(List.of(source.id())).scanRun().id();
        var accepted = background.start(scanId);
        assertEquals(3, accepted.job().executionVersion());
        assertEquals("PENDING", accepted.job().status());

        executor.runAccepted();

        assertEquals(1, count("job"));
        assertFalse(executor.workerTransaction);
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, accepted.job().id()));
    }

    @Test
    void rejectedSubmissionLeavesDurableFailure() throws Exception {
        var source = source("rejection");
        long scanId = requests.create(List.of(source.id())).scanRun().id();
        executor.reject = true;

        assertThrows(Version2SchedulingException.class, () -> background.start(scanId));

        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM job WHERE scan_run_id = ?", String.class, scanId));
        assertEquals(0, count("file_entry"));
    }

    @Test
    void ambientTransactionCannotStartBackgroundWork() throws Exception {
        var source = source("transaction");
        long scanId = requests.create(List.of(source.id())).scanRun().id();
        assertThrows(IllegalTransactionStateException.class,
                () -> new TransactionTemplate(transactions).execute(status -> background.start(scanId)));
        assertEquals(0, count("job"));
    }
}
