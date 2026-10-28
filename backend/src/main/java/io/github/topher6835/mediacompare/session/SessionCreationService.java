package io.github.topher6835.mediacompare.session;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

import io.github.topher6835.mediacompare.filesystem.HostFileSystem;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;

/** Creates one named Session child, without catalog ownership or runtime startup. */
public final class SessionCreationService {
    public record CreatedSession(String name, Path path, Session session) { }

    /** Structured recovery evidence; a failed create call may have made a directory before throwing. */
    public static final class CreationFailure extends IOException {
        private final Path attemptedPath;
        private final boolean directoryCreated;
        private final boolean mutationAttempted;

        private CreationFailure(String message, Path attemptedPath, boolean directoryCreated,
                boolean mutationAttempted, Exception cause) {
            super(message, cause);
            this.attemptedPath = attemptedPath;
            this.directoryCreated = directoryCreated;
            this.mutationAttempted = mutationAttempted;
        }

        public Path attemptedPath() { return attemptedPath; }
        public boolean directoryCreated() { return directoryCreated; }
        public boolean mutationAttempted() { return mutationAttempted; }
    }

    enum Phase { BEFORE_CREATE, AFTER_COLLISION, AFTER_CREATE, BEFORE_MANIFEST, AFTER_INITIALIZATION }

    @FunctionalInterface
    interface Checkpoint { void reached(Phase phase, Path child) throws IOException; }

    private final Checkpoint checkpoint;

    public SessionCreationService() { this((phase, child) -> { }); }

    // Controlled test gates keep replacement/disappearance tests independent of timing.
    SessionCreationService(Checkpoint checkpoint) { this.checkpoint = checkpoint; }

    public CreatedSession create(Path selectedParent, String requestedName) throws IOException {
        SessionName.validate(requestedName);
        if (selectedParent == null || !selectedParent.isAbsolute()) {
            throw new IllegalArgumentException("Select an absolute existing parent directory");
        }
        HostFileSystem host = HostFileSystems.current();
        Path parent;
        SessionDirectoryEvidence parentEvidence;
        try {
            parent = Session.normalizedRoot(selectedParent);
            parentEvidence = SessionDirectoryEvidence.capture(parent, host);
            revalidateParent(selectedParent, parentEvidence, host);
        } catch (IOException | SecurityException | IllegalArgumentException failure) {
            throw failure(selectedParent, false, false, failure);
        }
        for (int suffix = 0; suffix <= 999; suffix++) {
            String name = suffix == 0 ? requestedName
                    : requestedName + String.format(Locale.ROOT, " (%03d)", suffix);
            Path child = parent.resolve(name);
            boolean created = false;
            boolean mutationAttempted = false;
            try {
                checkpoint.reached(Phase.BEFORE_CREATE, child);
                revalidateParent(selectedParent, parentEvidence, host);
                try {
                    mutationAttempted = true;
                    Files.createDirectory(child);
                    created = true;
                } catch (FileAlreadyExistsException collision) {
                    mutationAttempted = false; // This call did not create a directory.
                    checkpoint.reached(Phase.AFTER_COLLISION, child);
                    revalidateParent(selectedParent, parentEvidence, host);
                    // A provider exception alone is insufficient. Confirm an entry without following links.
                    Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    revalidateParent(selectedParent, parentEvidence, host);
                    continue;
                }
                SessionDirectoryEvidence childEvidence = SessionDirectoryEvidence.capture(child, host);
                if (!childEvidence.directChildOf(parentEvidence)) {
                    throw new IOException("Created Session is no longer a direct child of the selected parent");
                }
                checkpoint.reached(Phase.AFTER_CREATE, child);
                checkpoint.reached(Phase.BEFORE_MANIFEST, child);
                Session session = Session.initializeEmptyDirectory(child, () -> {
                    revalidateParent(selectedParent, parentEvidence, host);
                    childEvidence.revalidate(host);
                });
                checkpoint.reached(Phase.AFTER_INITIALIZATION, child);
                revalidateParent(selectedParent, parentEvidence, host);
                childEvidence.revalidate(host);
                return new CreatedSession(name, session.root(), session);
            } catch (IOException | SecurityException | IllegalArgumentException failure) {
                throw failure(child, created, mutationAttempted, failure);
            }
        }
        throw new CreationFailure("All 1,000 Session names are occupied under " + display(parent)
                + "; choose another name or parent directory", parent, false, false, null);
    }

    private static void revalidateParent(Path selected, SessionDirectoryEvidence evidence,
            HostFileSystem host) throws IOException {
        if (!evidence.path().equals(Session.normalizedRoot(selected))) {
            throw new IOException("Selected parent now resolves to a different directory");
        }
        evidence.revalidate(host);
    }

    private static CreationFailure failure(Path path, boolean created, boolean mutationAttempted, Exception cause) {
        String advice = created
                ? "; initialization did not complete. The directory was not removed. Inspect it before retrying."
                : "; creation was not confirmed. Inspect this location and check storage/permissions before retrying.";
        return new CreationFailure("Cannot create Session at " + display(path) + advice,
                path, created, mutationAttempted, cause);
    }

    private static String display(Path path) {
        String text = path.toString().replaceAll("\\p{Cntrl}", "?");
        return text.length() <= 512 ? text : text.substring(0, 240) + "..." + text.substring(text.length() - 269);
    }
}
