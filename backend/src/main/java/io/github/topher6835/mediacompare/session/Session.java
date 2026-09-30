package io.github.topher6835.mediacompare.session;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One portable folder containing a manifest, catalog, and derived cache. */
public final class Session {
    private static final String MANIFEST = """
            {
              "type": "media-compare-session",
              "formatVersion": 1
            }
            """;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Path root;

    private Session(Path root) {
        this.root = root;
    }

    public static Session open(Path selectedRoot) throws IOException {
        Path root = normalizedRoot(selectedRoot);
        requireDirectory(root, "Session root");
        Path manifest = child(root, "session.json");
        requireRegularFile(manifest, "Session manifest");
        JsonNode document;
        try {
            document = JSON.readTree(Files.readString(manifest));
        } catch (JacksonException exception) {
            throw new IOException("Invalid Session manifest JSON: " + manifest, exception);
        }
        if (document == null || !document.isObject()) {
            throw new IOException("Session manifest must be a JSON object: " + manifest);
        }
        JsonNode type = document.get("type");
        JsonNode version = document.get("formatVersion");
        if (type == null || !type.isTextual() || !"media-compare-session".equals(type.textValue())) {
            throw new IOException("Session manifest has missing or invalid type: " + manifest);
        }
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() != 1) {
            throw new IOException("Unsupported or missing Session manifest formatVersion: " + manifest);
        }
        if (document.size() != 2) {
            throw new IOException("Session manifest contains unsupported fields: " + manifest);
        }
        checkExistingChild(child(root, "catalog.db"), false);
        checkExistingChild(child(root, "catalog.db.lock"), false);
        checkExistingChild(child(root, "catalog.db-wal"), false);
        checkExistingChild(child(root, "catalog.db-shm"), false);
        checkExistingChild(child(root, "catalog.db-journal"), false);
        checkExistingChild(child(root, "cache"), true);
        checkExistingChild(child(root, "cache", "previews"), true);
        return new Session(root);
    }

    public static Session create(Path selectedRoot) throws IOException {
        Path root = normalizedRoot(selectedRoot);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectory(root, "Session root");
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new FileAlreadyExistsException("Session target is not empty: " + root);
                }
            }
        } else {
            Files.createDirectories(root);
        }
        Files.writeString(child(root, "session.json"), MANIFEST, StandardOpenOption.CREATE_NEW);
        return open(root);
    }

    /** Removes only recognized workspace contents after proving catalog ownership is idle. */
    public static void delete(Path selectedRoot) throws IOException {
        SessionDeletion.delete(open(selectedRoot));
    }

    public Path root() { return root; }
    public Path manifestPath() { return child(root, "session.json"); }
    public Path catalogPath() { return child(root, "catalog.db"); }
    public Path previewCacheRoot() { return child(root, "cache", "previews"); }
    public String catalogJdbcUrl() { return "jdbc:sqlite:" + catalogPath().toUri() + "?foreign_keys=on"; }

    private static Path normalizedRoot(Path selectedRoot) throws IOException {
        if (selectedRoot == null || selectedRoot.toString().isBlank()) {
            throw new IOException("media-compare.session-root must select a Session folder");
        }
        Path root = selectedRoot.toAbsolutePath().normalize();
        // Resolve trusted parent aliases while rejecting a linked Session root itself.
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Session root must not be a symbolic link: " + root);
        }
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return root.toRealPath();
        }
        return root;
    }

    private static Path child(Path root, String... names) {
        Path result = root;
        for (String name : names) result = result.resolve(name);
        result = result.normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("Session path escaped its root");
        return result;
    }

    private static void requireDirectory(Path path, String description) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a regular directory: " + path);
        }
    }

    private static void requireRegularFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a regular file: " + path);
        }
    }

    private static void checkExistingChild(Path path, boolean directory) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (directory) requireDirectory(path, "Session cache path");
            else requireRegularFile(path, "Session catalog path");
        }
    }
}
