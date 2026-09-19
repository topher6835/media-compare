package io.github.topher6835.mediacompare.process;

/** Signals caller interruption after the owned child and reader threads were cleaned up. */
public final class BoundedProcessInterruptedException extends RuntimeException {
    public BoundedProcessInterruptedException() {
        super("Bounded process execution was interrupted");
    }
}
