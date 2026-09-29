package io.github.topher6835.mediacompare.preview;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import javax.imageio.ImageIO;
/** Write companion to the resolver. Only operates within the application-owned cache. */
public class PreviewCacheWriter {
    private final Path configuredRoot;
    private final PreviewCacheResolver resolver;

    public PreviewCacheWriter(String cacheRoot,
            PreviewCacheResolver resolver) {
        if (cacheRoot == null || cacheRoot.isBlank()) {
            throw new IllegalArgumentException("preview-cache-root must not be blank");
        }
        this.configuredRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
        this.resolver = resolver;
    }

    public Path createTemporary(String relativePath, String assetKey) throws IOException {
        Path target = prepareTarget(relativePath, assetKey);
        return Files.createTempFile(target.getParent(), ".thumbnail-", ".tmp");
    }

    public long validateTemporary(Path temporary, SmallThumbnailRenderer.Dimensions dimensions) throws IOException {
        return inspectPng(temporary, dimensions.width(), dimensions.height());
    }

    public void validatePublished(PreviewAsset asset) throws IOException {
        requireOutputMetadata(asset);
        resolver.requireRegularFile(asset.relativePath(), asset.assetSizeBytes());
        Path target = existingTarget(asset.relativePath());
        if (inspectPng(target, asset.pixelWidth(), asset.pixelHeight()) != asset.assetSizeBytes()) {
            throw new IOException("Published preview size disagrees with metadata");
        }
    }

    /** Atomic no-clobber publication. A hard link gives CREATE_NEW semantics on macOS and Windows. */
    public boolean publish(Path temporary, PreviewAsset asset) throws IOException {
        requireOutputMetadata(asset);
        Path target = prepareTarget(asset.relativePath(), asset.assetKey());
        if (!temporary.getParent().equals(target.getParent())) {
            throw new IOException("Temporary preview is outside the target cache directory");
        }
        if (inspectPng(temporary, asset.pixelWidth(), asset.pixelHeight()) != asset.assetSizeBytes()) {
            throw new IOException("Temporary preview size disagrees with metadata");
        }
        try {
            // Files.move(..., ATOMIC_MOVE) can replace a target even without REPLACE_EXISTING.
            // Linking the closed same-filesystem temporary inode cannot replace an existing name.
            // Providers without hard-link support fail closed rather than weaken immutability.
            Files.createLink(target, temporary);
            validatePublished(asset);
            return true;
        } catch (FileAlreadyExistsException equivalentRace) {
            validatePublished(asset);
            if (Files.mismatch(temporary, target) != -1) {
                throw new IOException("Existing immutable preview is not byte-equivalent");
            }
            return false;
        }
    }

    private Path prepareTarget(String relativePath, String assetKey) throws IOException {
        PreviewCacheLayout.requireAssetPath(relativePath, PreviewKind.SMALL_THUMBNAIL, assetKey);
        if (configuredRoot.getParent() != null) {
            Files.createDirectories(configuredRoot.getParent());
        }
        createDirectory(configuredRoot);
        Path root = configuredRoot.toRealPath();
        Path target = root;
        String[] components = relativePath.split("/");
        for (int index = 0; index < components.length; index++) {
            target = target.resolve(components[index]);
            if (!target.startsWith(root)) {
                throw new IOException("Preview escaped the cache root");
            }
            if (index < components.length - 1) {
                createDirectory(target);
            }
        }
        return target;
    }

    private Path existingTarget(String relativePath) throws IOException {
        Path root = configuredRoot.toRealPath();
        Path target = root;
        for (String component : relativePath.split("/")) {
            target = target.resolve(component);
        }
        return target;
    }

    private static void createDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException alreadyCreated) {
            // Validate the existing entry below, including competing directory creation.
        }
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("Cache directory is unsafe");
        }
    }

    private static long inspectPng(Path file, int width, int height) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.size() <= 0
                || width <= 0 || height <= 0
                || Math.max(width, height) > SmallThumbnailDefinition.MAX_EDGE) {
            throw new IOException("Preview output is not a valid bounded regular file");
        }
        try (var input = new PreviewImageInput(file)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Preview output has no PNG reader");
            }
            var reader = readers.next();
            try {
                reader.setInput(input, false, true);
                if (!reader.getFormatName().equalsIgnoreCase("PNG")
                        || reader.getWidth(0) != width || reader.getHeight(0) != height) {
                    throw new IOException("Preview format/dimensions disagree with renderer");
                }
                var image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) {
                    throw new IOException("Preview PNG cannot be decoded");
                }
            } finally {
                reader.dispose();
            }
        }
        return attributes.size();
    }

    private static void requireOutputMetadata(PreviewAsset asset) throws IOException {
        if (asset.kind() != PreviewKind.SMALL_THUMBNAIL
                || !asset.definition().equals(SmallThumbnailDefinition.definition())
                || !asset.mediaType().equals(SmallThumbnailDefinition.MEDIA_TYPE)
                || !asset.relativePath().equals(PreviewCacheLayout.relativePath(asset.kind(),
                        asset.assetKey(), SmallThumbnailDefinition.EXTENSION))) {
            throw new IOException("Preview metadata is incompatible with this generator");
        }
    }
}
