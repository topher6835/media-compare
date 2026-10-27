package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.topher6835.mediacompare.session.Session;

/** Read-only navigation. Validation delegates to Session and never takes catalog ownership. */
final class FilesystemBrowser {
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_SCANNED = 10_000;
    private static final int MAX_FILTER_LENGTH = 256;

    static final class InvalidFilterException extends IllegalArgumentException {
        InvalidFilterException() {
            super("Folder name filter must be at most " + MAX_FILTER_LENGTH + " characters.");
        }
    }

    List<Map<String, String>> roots() {
        var roots = new LinkedHashMap<String, Map<String, String>>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) addRoot(roots, root, root.toString());
        addRoot(roots, Path.of(System.getProperty("user.home")), "Home");
        return new ArrayList<>(roots.values());
    }

    private void addRoot(Map<String, Map<String, String>> roots, Path path, String name) {
        if (Files.isDirectory(path)) roots.putIfAbsent(path.toString(), Map.of("name", name, "path", path.toString()));
    }

    Path absolutePath(String text) {
        if (text == null || text.isEmpty() || text.length() > 16_384 || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Enter an absolute directory path.");
        }
        Path path;
        try { path = Path.of(text); }
        catch (InvalidPathException invalid) { throw new IllegalArgumentException("The directory path is invalid."); }
        if (!path.isAbsolute()) throw new IllegalArgumentException("Enter an absolute directory path.");
        // Preserve legitimate spaces and host path spelling. No shell, URL or file-content interpretation.
        return path.normalize();
    }

    Map<String, Object> list(Path path, String filter) throws IOException {
        if (filter.length() > MAX_FILTER_LENGTH) throw new InvalidFilterException();
        String normalizedFilter = filter.toLowerCase(Locale.ROOT);
        if (!Files.isDirectory(path)) {
            if (Files.notExists(path)) throw new NoSuchFileException("Directory not found");
            throw new IOException("Directory unavailable");
        }
        var entries = new ArrayList<Map<String, String>>();
        boolean truncated = false;
        int scanned = 0;
        try (var stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                if (++scanned > MAX_SCANNED) { truncated = true; break; }
                if (!child.getFileName().toString().toLowerCase(Locale.ROOT).contains(normalizedFilter)) continue;
                if (Files.isDirectory(child)) {
                    if (entries.size() == MAX_ENTRIES) { truncated = true; break; }
                    entries.add(Map.of("name", child.getFileName().toString(), "path", child.toString()));
                }
            }
        } catch (DirectoryIteratorException exception) { throw exception.getCause(); }
        entries.sort(Comparator.comparing(entry -> entry.get("name"), String.CASE_INSENSITIVE_ORDER));
        var result = new LinkedHashMap<String, Object>();
        result.put("path", path.toString());
        result.put("parent", path.getParent() == null ? null : path.getParent().toString());
        result.put("directories", entries);
        result.put("truncated", truncated);
        return result;
    }

    Map<String, String> validate(Path path) {
        if (!Files.isDirectory(path) || !Files.isReadable(path)) return validation("UNAVAILABLE", "The folder is missing or inaccessible.");
        Path manifest = path.resolve("session.json");
        if (Files.notExists(manifest, LinkOption.NOFOLLOW_LINKS)) return validation("DIRECTORY", "This is an ordinary folder, without a Session manifest.");
        try {
            Session session = Session.open(path);
            if (!Files.isWritable(session.root()) || (Files.exists(session.catalogPath()) && !Files.isWritable(session.catalogPath()))) {
                return validation("CANNOT_OPEN", "The Session requires write access before it can be opened.");
            }
            return validation("VALID_SESSION", "Session structure is valid. Catalog integrity, ownership and initialization are checked when opening.");
        } catch (IOException | RuntimeException failure) {
            return validation("INVALID_SESSION", "The Session is unsupported, invalid or inaccessible. Linked Session roots and workspace files are not accepted.");
        }
    }

    private Map<String, String> validation(String kind, String message) { return Map.of("kind", kind, "message", message); }
}
