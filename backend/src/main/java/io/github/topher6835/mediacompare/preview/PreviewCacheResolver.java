package io.github.topher6835.mediacompare.preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only managed-cache resolution. Creates no directories or files. */
@Component
public class PreviewCacheResolver {
    private final Path configuredRoot;

    public PreviewCacheResolver(@Value("${media-compare.preview-cache-root:data/cache/previews}") String cacheRoot) {
        if (cacheRoot == null || cacheRoot.isBlank()) {
            throw new IllegalArgumentException("preview-cache-root must not be blank");
        }
        configuredRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
    }

    public void requireRegularFile(String relativePath, long expectedSize) throws IOException {
        checkedFile(relativePath, expectedSize);
    }

    /** Opens with NOFOLLOW_LINKS rather than reopening a checked path through a Resource. */
    public InputStream open(String relativePath, long expectedSize) throws IOException {
        CheckedFile before = checkedFile(relativePath, expectedSize);
        SeekableByteChannel channel = Files.newByteChannel(before.path(),
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try {
            CheckedFile after = checkedFile(relativePath, expectedSize);
            if (!before.path().equals(after.path())
                    || !Objects.equals(before.attributes().fileKey(), after.attributes().fileKey())
                    || channel.size() != expectedSize) {
                throw new IOException("Cache file changed while opening");
            }
            return Channels.newInputStream(channel);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private CheckedFile checkedFile(String relativePath, long expectedSize) throws IOException {
        PreviewCacheLayout.requireSafeRelativePath(relativePath);
        if (expectedSize <= 0) {
            throw new IllegalArgumentException("expectedSize must be positive");
        }
        requireDirectory(configuredRoot);
        // The configured root is trusted; real-path resolution permits host aliases such as macOS /var.
        // The root itself and every cache-relative directory/file must be non-symbolic links.
        Path root = configuredRoot.toRealPath();
        Path resolved = root;
        String[] components = relativePath.split("/");
        for (int index = 0; index < components.length; index++) {
            resolved = resolved.resolve(components[index]);
            if (!resolved.startsWith(root)) {
                throw new IOException("Cache path escaped the managed root");
            }
            if (index < components.length - 1) {
                requireDirectory(resolved);
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(resolved, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() != expectedSize
                || !resolved.toRealPath().equals(resolved)) {
            throw new IOException("Cache asset is missing, unsafe, or has unexpected size");
        }
        return new CheckedFile(resolved, attributes);
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Cache path contains a non-directory or symbolic link");
        }
    }

    private record CheckedFile(Path path, BasicFileAttributes attributes) {
    }
}
