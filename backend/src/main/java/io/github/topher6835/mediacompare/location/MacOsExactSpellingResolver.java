package io.github.topher6835.mediacompare.location;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Resolves a Unix location to spelling observed through a symlink-free directory walk. */
public final class MacOsExactSpellingResolver {

    private final FileSystemAccess fileSystem;
    private final BooleanSupplier macOs;

    public MacOsExactSpellingResolver() {
        this(new NioFileSystemAccess(), MacOsExactSpellingResolver::isMacOsHost);
    }

    MacOsExactSpellingResolver(FileSystemAccess fileSystem, BooleanSupplier macOs) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.macOs = Objects.requireNonNull(macOs, "macOs");
    }

    public ContinuityProbeResult<LocationPath> resolve(LocationPath requested) {
        Objects.requireNonNull(requested, "requested");
        if (!macOs.getAsBoolean() || requested.dialect() != LocationDialect.UNIX) {
            return ContinuityProbeResult.unsupported();
        }

        try {
            Path current = Path.of("/");
            PathClassification root = fileSystem.classify(current);
            if (!root.directory() || root.symbolicLink()) {
                return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
            }

            List<String> exactComponents = new ArrayList<>(requested.components().size());
            for (int index = 0; index < requested.components().size(); index++) {
                String requestedComponent = requested.components().get(index);
                Path selected = selectEntry(current, requestedComponent);
                if (selected == null) {
                    return ContinuityProbeResult.unavailable();
                }
                PathClassification classification = fileSystem.classify(selected);
                boolean intermediate = index + 1 < requested.components().size();
                if (classification.symbolicLink() || intermediate && !classification.directory()) {
                    return ContinuityProbeResult.uncertain(ContinuityReason.PROBE_UNCERTAIN);
                }
                exactComponents.add(selected.getFileName().toString());
                current = selected;
            }

            return ContinuityProbeResult.accepted(
                    new LocationPath(LocationDialect.UNIX, List.of(), exactComponents));
        } catch (NoSuchFileException exception) {
            return ContinuityProbeResult.unavailable();
        } catch (IOException | SecurityException exception) {
            return ContinuityProbeResult.unavailable();
        } catch (RuntimeException exception) {
            return ContinuityProbeResult.error();
        }
    }

    Path toHostPath(LocationPath exactLocation) {
        if (exactLocation.dialect() != LocationDialect.UNIX) {
            throw new IllegalArgumentException("macOS host paths require the Unix dialect");
        }
        Path path = Path.of("/");
        for (String component : exactLocation.components()) {
            path = path.resolve(component);
        }
        return path;
    }

    private Path selectEntry(Path parent, String requestedComponent) throws IOException {
        List<Path> entries = fileSystem.list(parent);
        for (Path entry : entries) {
            if (entry.getFileName().toString().equals(requestedComponent)) {
                return entry;
            }
        }

        Path requestedPath = parent.resolve(requestedComponent);
        Path match = null;
        for (Path entry : entries) {
            if (fileSystem.isSameFile(requestedPath, entry)) {
                if (match != null) {
                    return null;
                }
                match = entry;
            }
        }
        return match;
    }

    private static boolean isMacOsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    interface FileSystemAccess {
        List<Path> list(Path directory) throws IOException;

        PathClassification classify(Path path) throws IOException;

        boolean isSameFile(Path first, Path second) throws IOException;
    }

    record PathClassification(boolean directory, boolean symbolicLink) {
    }

    private static final class NioFileSystemAccess implements FileSystemAccess {
        @Override
        public List<Path> list(Path directory) throws IOException {
            var entries = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) {
                    entries.add(entry);
                }
            }
            return List.copyOf(entries);
        }

        @Override
        public PathClassification classify(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new PathClassification(attributes.isDirectory(), attributes.isSymbolicLink());
        }

        @Override
        public boolean isSameFile(Path first, Path second) throws IOException {
            return Files.isSameFile(first, second);
        }
    }
}
