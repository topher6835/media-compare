package io.github.topher6835.mediacompare.config;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** The OS lock, not the existence of the sidecar file, establishes ownership. */
public final class CatalogOwnership implements AutoCloseable {
    // On some platforms closing another channel for the same file releases process locks.
    // Avoid opening that second channel in this JVM; the OS lock still excludes other processes.
    private static final Set<Path> OWNED_PATHS = new HashSet<>();
    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean retainedUntilExit;

    private CatalogOwnership(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    public static synchronized CatalogOwnership acquire(String jdbcUrl) throws IOException {
        Path catalog = catalogPath(jdbcUrl);
        // SQLite in-memory catalogs cannot be shared with another process (used by tests).
        if (catalog == null) {
            return new CatalogOwnership(null, null, null);
        }
        Files.createDirectories(catalog.getParent());
        catalog = Files.exists(catalog, LinkOption.NOFOLLOW_LINKS) ? catalog.toRealPath()
                : catalog.getParent().toRealPath().resolve(catalog.getFileName());
        Path lockPath = catalog.resolveSibling(catalog.getFileName() + ".lock");
        if (OWNED_PATHS.contains(lockPath)) {
            throw alreadyInUse();
        }
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw alreadyInUse();
            }
            OWNED_PATHS.add(lockPath);
            return new CatalogOwnership(lockPath, channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw alreadyInUse();
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    private static Path catalogPath(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
            throw new IllegalArgumentException("Media Compare requires a local SQLite catalog");
        }
        String location = jdbcUrl.substring("jdbc:sqlite:".length());
        String[] parts = location.split("\\?", 2);
        if (parts[0].equals(":memory:") || (parts[0].startsWith("file:")
                && (parts[0].equals("file::memory:")
                    || (parts.length == 2 && java.util.Arrays.asList(parts[1].split("&")).contains("mode=memory"))))) {
            return null;
        }
        if (parts[0].isBlank() || parts[0].startsWith(":resource:")) {
            throw new IllegalArgumentException("A persistent local SQLite catalog path is required");
        }
        String path = parts[0];
        if (path.startsWith("file:")) {
            URI uri = URI.create(path);
            path = uri.isOpaque() ? uri.getSchemeSpecificPart() : Path.of(uri).toString();
        }
        return Path.of(path).toAbsolutePath();
    }

    private static IllegalStateException alreadyInUse() {
        return new IllegalStateException("Media Compare catalog is already in use by another backend process.");
    }

    public Path lockPath() {
        return lockPath;
    }

    /** Never let another backend recover work while an uncooperative worker may still write. */
    public synchronized void retainUntilProcessExit() {
        retainedUntilExit = true;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!retainedUntilExit && channel != null && channel.isOpen()) {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } finally {
                channel.close();
                synchronized (CatalogOwnership.class) {
                    OWNED_PATHS.remove(lockPath);
                }
            }
        }
    }
}
