package io.github.topher6835.mediacompare.session;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

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
        source = source.normalize();
        if (source.startsWith(sessionRoot) || sessionRoot.startsWith(source)) {
            throw new SourceWorkspaceOverlapException();
        }
    }
}
