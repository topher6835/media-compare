package io.github.topher6835.mediacompare.management;

/** A failed child may still be alive: only processAlive=false confirms release. */
public record CatalogStatus(State state, boolean processAlive, Integer port, Integer exitCode, Failure failure) {
    public enum State { NOT_STARTED, CREATED, STARTING, RUNNING, STOPPING, FAILED }

    public enum Failure {
        PROCESS_START_FAILED, INVALID_SESSION, OWNERSHIP_FAILED, INITIALIZATION_FAILED,
        STARTUP_FAILED, STARTUP_TIMEOUT, UNEXPECTED_EXIT, CONTROL_FAILED, SHUTDOWN_FAILED, SHUTDOWN_TIMEOUT
    }
}
