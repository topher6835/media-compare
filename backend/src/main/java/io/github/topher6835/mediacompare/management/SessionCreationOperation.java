package io.github.topher6835.mediacompare.management;

/** Mutable only under SessionCreationOperations' monitor. No filesystem or process authority is stored here. */
final class SessionCreationOperation {
    enum Kind { CREATE, RETRY_OPEN }
    enum State { RESERVED, CREATING, OPENING, RUNNING, REJECTED, CREATION_UNCERTAIN, OPEN_FAILED }

    final String id;
    final Kind kind;
    final String creationId;
    final String previousAttemptId;
    final long createdAtMs = System.currentTimeMillis();
    final long createdTick;
    String latestAttemptId;
    SessionCreationOperations.Input input;
    State state = State.RESERVED;
    Long completedAtMs;
    long completedTick;
    String name;
    String path;
    String attemptedPath;
    boolean directoryCreated;
    String catalogId;
    String openingFailureCode;
    String errorCode;
    String message;
    SessionLifecycleAdmission.Lease lease;
    Thread runner;

    SessionCreationOperation(String id, Kind kind, String creationId, String previousAttemptId, long tick) {
        this.id = id;
        this.kind = kind;
        this.creationId = creationId == null ? id : creationId;
        this.previousAttemptId = previousAttemptId;
        latestAttemptId = id;
        createdTick = tick;
    }
}
