package io.github.topher6835.mediacompare.management;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.session.SessionCreationService;

/** Read-only error classification followed by the Task 1 creator, which repeats all safety checks. */
final class SessionCreationActions {
    private SessionCreationActions() { }

    static SessionCreationService.CreatedSession create(SessionCreationOperations.Input input) throws IOException {
        Path parent = new FilesystemBrowser().absolutePath(input.parentPath());
        try {
            var attributes = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) throw new IOException("Not a directory");
            var host = HostFileSystems.current();
            if (!host.supportsProfile(host.profile(parent))) {
                throw new SessionCreationOperations.Failure(422, "UNSUPPORTED_STORAGE",
                        "Select supported local macOS/APFS or Windows/NTFS Session storage.");
            }
        } catch (IOException | SecurityException unavailable) {
            throw new SessionCreationOperations.Failure(422, "PARENT_UNAVAILABLE",
                    "The parent directory is unavailable or inaccessible. Check it before retrying.");
        } catch (UnsupportedOperationException unsupported) {
            throw new SessionCreationOperations.Failure(422, "UNSUPPORTED_STORAGE", "Session creation is unsupported on this host.");
        }
        return new SessionCreationService().create(parent, input.name());
    }
}
