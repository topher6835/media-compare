package io.github.topher6835.mediacompare.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Pure structural guard between configured Source roots and the active workspace. */
public class SessionSourceBoundary {
    private final Path sessionRoot;

    public SessionSourceBoundary(Path validatedSessionRoot) {
        sessionRoot = validatedSessionRoot;
    }

    public void requireSeparate(String sourceRoot) {
        if (sessionRoot == null) return; // Isolated catalog tests have no Session.
        Path source;
        try {
            source = Path.of(sourceRoot);
        } catch (InvalidPathException exception) {
            // A portable Session may contain a Source path from another host OS.
            return;
        }
        if (!source.isAbsolute()) return; // Foreign-host paths cannot contain this host's Session.
        if (!source.getRoot().equals(sessionRoot.getRoot())
                && Files.notExists(source.getRoot(), LinkOption.NOFOLLOW_LINKS)) return;
        source = canonicalizeExistingAncestor(source.normalize());
        if (source.startsWith(sessionRoot) || sessionRoot.startsWith(source)) {
            throw new SourceWorkspaceOverlapException();
        }
    }

    private static Path canonicalizeExistingAncestor(Path path) {
        var missing = new ArrayDeque<Path>();
        Path ancestor = path;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                throw new SourceWorkspaceOverlapException();
            }
            if (ancestor.getFileName() == null) throw new SourceWorkspaceOverlapException();
            missing.addFirst(ancestor.getFileName());
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) throw new SourceWorkspaceOverlapException();
        try {
            Path canonical = ancestor.toRealPath();
            for (Path name : missing) canonical = canonical.resolve(name);
            return canonical.normalize();
        } catch (IOException | SecurityException exception) {
            // An alias that cannot be resolved cannot establish workspace separation.
            throw new SourceWorkspaceOverlapException();
        }
    }
}
