package io.github.topher6835.mediacompare.scan;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

/** Cooperative worker termination is not a failed/skipped file candidate. */
public class IndexingInterruptedException extends RuntimeException {
    private IndexingInterruptedException() {
        super("Execution interrupted");
    }

    public static void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IndexingInterruptedException();
        }
    }

    public static void propagateIfInterrupted(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException
                    || cause instanceof ClosedByInterruptException || cause instanceof IndexingInterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        check();
    }
}
