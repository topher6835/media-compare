package io.github.topher6835.mediacompare.matching;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.topher6835.mediacompare.process.BoundedProcessExecutor;
import org.junit.jupiter.api.Test;

class FinderRevealProcessTests {
    @Test
    void directCommandUsesConstantExecutableSeparateArgumentsAndTimeout() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();
        var process = new FinderRevealProcess((args, limit) -> {
            command.set(args);
            timeout.set(limit);
            return new BoundedProcessExecutor.ExecutionFinished(0, new byte[0], new byte[0]);
        });
        assertTrue(process.reveal(Path.of("/safe/a file.heic")));
        assertEquals(List.of("/usr/bin/open", "-R", "/safe/a file.heic"), command.get());
        assertEquals(Duration.ofSeconds(5), timeout.get());
    }

    @Test
    void processFailureDoesNotClaimSuccess() {
        var process = new FinderRevealProcess((args, timeout) ->
                new BoundedProcessExecutor.ExecutionFailed(
                        BoundedProcessExecutor.ExecutionFailure.TIMEOUT, new byte[0]));
        assertFalse(process.reveal(Path.of("/safe/file.jpg")));
    }
}
