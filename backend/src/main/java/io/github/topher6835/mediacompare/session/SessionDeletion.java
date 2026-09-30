package io.github.topher6835.mediacompare.session;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;

/** Allowlisted deletion of one closed Session. Never reads Source records or paths. */
final class SessionDeletion {
    private static final String[] SQLITE_SIDECARS = {
            "catalog.db-wal", "catalog.db-shm", "catalog.db-journal"
    };

    private SessionDeletion() {
    }

    static void delete(Session session) throws IOException {
        Path root = session.root();
        // The same lock used by startup excludes an active backend. Its file may be stale.
        try (CatalogOwnership ignored = CatalogOwnership.acquire(session.catalogJdbcUrl())) {
            for (String name : SQLITE_SIDECARS) requireRegularIfPresent(root.resolve(name));
            requireRegularIfPresent(session.catalogPath());
            requireRegularIfPresent(root.resolve("catalog.db.lock"));
            checkCache(root.resolve("cache"));

            deleteCache(root.resolve("cache"));
            for (String name : SQLITE_SIDECARS) Files.deleteIfExists(root.resolve(name));
            Files.deleteIfExists(session.catalogPath());
            Files.delete(session.manifestPath());
        }
        // Close the channel before unlinking its file; Windows cannot remove an open lock file.
        // The manifest is already gone, so a new normal startup fails validation.
        Files.delete(root.resolve("catalog.db.lock"));
        try {
            Files.delete(root);
        } catch (DirectoryNotEmptyException exception) {
            throw new DirectoryNotEmptyException("Session-owned data was removed, but unknown entries remain in " + root);
        }
    }

    private static void requireRegularIfPresent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || HostFileSystems.current().unsafeElement(path, attributes)) {
                throw new IOException("Session-owned file is not a regular file: " + path);
            }
        }
    }

    private static void checkCache(Path cache) throws IOException {
        if (!Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(cache, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isDirectory() || HostFileSystems.current().unsafeElement(directory, attributes)) {
                    throw new IOException("Session cache contains an unsafe directory: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isRegularFile() || HostFileSystems.current().unsafeElement(file, attributes)) {
                    throw new IOException("Session cache contains an unsafe entry: " + file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteCache(Path cache) throws IOException {
        if (!Files.exists(cache, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(cache, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isDirectory() || HostFileSystems.current().unsafeElement(directory, attributes)) {
                    throw new IOException("Session cache changed during deletion: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isRegularFile() || HostFileSystems.current().unsafeElement(file, attributes)) {
                    throw new IOException("Session cache changed during deletion: " + file);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
