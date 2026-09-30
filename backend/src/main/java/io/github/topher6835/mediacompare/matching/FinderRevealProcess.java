package io.github.topher6835.mediacompare.matching;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.topher6835.mediacompare.process.BoundedProcessExecutor;
import org.springframework.stereotype.Component;

/** Direct, bounded macOS Finder selection. Receives only a validated backend path. */
@Component
public class FinderRevealProcess {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final CommandRunner runner;

    public FinderRevealProcess() {
        var executor = new BoundedProcessExecutor("finder-reveal-reader-");
        runner = (command, timeout) -> executor.execute(command, null, timeout,
                Duration.ofMillis(250), Duration.ofSeconds(1), 4096, 4096);
    }

    FinderRevealProcess(CommandRunner runner) {
        this.runner = runner;
    }

    public boolean reveal(Path validatedFile) {
        var result = runner.execute(List.of("/usr/bin/open", "-R", validatedFile.toString()), TIMEOUT);
        return result instanceof BoundedProcessExecutor.ExecutionFinished finished && finished.exitCode() == 0;
    }

    @FunctionalInterface
    interface CommandRunner {
        BoundedProcessExecutor.Execution execute(List<String> command, Duration timeout);
    }
}
