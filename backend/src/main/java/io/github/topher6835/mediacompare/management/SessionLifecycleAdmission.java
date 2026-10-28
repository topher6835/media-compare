package io.github.topher6835.mediacompare.management;

import java.nio.file.Path;

/** One Open/New admission, held across filesystem work without holding this or supervisor locks. */
final class SessionLifecycleAdmission {
    record Launch(String catalogId, CatalogStatus status) { }

    private final CatalogProcessSupervisor catalog;
    private Lease owner;
    private boolean closing;

    SessionLifecycleAdmission(CatalogProcessSupervisor catalog) { this.catalog = catalog; }

    synchronized Lease acquire() {
        if (closing) throw new SessionCreationOperations.Failure(503, "MANAGEMENT_STOPPING",
                "Management is stopping. Inspect any attempted Session before restarting.");
        if (owner != null || catalog.status().processAlive()) {
            throw new SessionCreationOperations.Failure(409, "LIFECYCLE_BUSY",
                    "Another Session operation or catalog is active. Wait for it to fully exit.");
        }
        owner = new Lease();
        return owner;
    }

    synchronized void stopAccepting() { closing = true; }

    final class Lease implements AutoCloseable {
        Launch start(Path path) {
            synchronized (SessionLifecycleAdmission.this) {
                if (closing || owner != this) throw new IllegalStateException("Session admission is closed");
                synchronized (catalog) {
                    CatalogStatus status = catalog.start(path);
                    return new Launch(catalog.catalogId(), status);
                }
            }
        }

        @Override public void close() {
            synchronized (SessionLifecycleAdmission.this) {
                if (owner == this) owner = null;
            }
        }
    }
}
