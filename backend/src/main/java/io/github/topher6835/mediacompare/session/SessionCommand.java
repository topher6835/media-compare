package io.github.topher6835.mediacompare.session;

import java.io.IOException;
import java.nio.file.Path;

/** One-shot lifecycle commands; does not construct a Spring catalog runtime. */
public final class SessionCommand {
    private SessionCommand() {
    }

    public static void run(String[] args) throws IOException {
        if (args.length != 3 || !"session".equals(args[0])) {
            throw new IllegalArgumentException("Usage: session create <path> | session delete <path>");
        }
        Path path = Path.of(args[2]);
        switch (args[1]) {
            case "create" -> Session.create(path);
            case "delete" -> Session.delete(path);
            default -> throw new IllegalArgumentException("Usage: session create <path> | session delete <path>");
        }
    }
}
