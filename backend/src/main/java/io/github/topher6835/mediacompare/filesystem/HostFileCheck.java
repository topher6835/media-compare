package io.github.topher6835.mediacompare.filesystem;

import java.nio.file.Path;
import java.util.Objects;

/** A path and an opaque, operation-local file identity; never persisted as catalog identity. */
public final class HostFileCheck {
    private final HostFileStatus status;
    private final Path path;
    private final Object fileKey;

    private HostFileCheck(HostFileStatus status, Path path, Object fileKey) {
        this.status = Objects.requireNonNull(status);
        this.path = path;
        this.fileKey = fileKey;
    }

    static HostFileCheck established(Path path, Object fileKey) {
        return new HostFileCheck(HostFileStatus.ESTABLISHED, path, fileKey);
    }

    static HostFileCheck failed(HostFileStatus status) {
        if (status == HostFileStatus.ESTABLISHED) throw new IllegalArgumentException("Use established result");
        return new HostFileCheck(status, null, null);
    }

    public HostFileStatus status() { return status; }

    public Path path() {
        if (status != HostFileStatus.ESTABLISHED) throw new IllegalStateException("No validated path");
        return path;
    }

    public boolean sameFileAs(HostFileCheck other) {
        return status == HostFileStatus.ESTABLISHED && other != null
                && other.status == HostFileStatus.ESTABLISHED
                && path.equals(other.path) && fileKey.equals(other.fileKey);
    }
}
