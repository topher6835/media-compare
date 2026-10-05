package io.github.topher6835.mediacompare.analysis;

import java.time.Duration;

/** Exposes the existing deterministic shutdown budget to cross-package ownership integration tests. */
public final class ExfatSlice5ExecutorFixture {
    private ExfatSlice5ExecutorFixture() { }
    public static void stop(MediaMetadataExecutor executor) { executor.shutdown(Duration.ZERO); }
}
