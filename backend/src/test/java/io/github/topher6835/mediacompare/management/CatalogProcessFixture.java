package io.github.topher6835.mediacompare.management;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

/** Real helper JVM with file gates for deterministic exit and stalled-cleanup scenarios. */
public final class CatalogProcessFixture {
    public static void main(String[] args) throws Exception {
        String token = args[1];
        Path root = Path.of(CatalogChildCommand.sessionPath(args[2]));
        String mode = root.getFileName().toString();
        System.out.println("MC_CATALOG " + token + " INITIALIZING");
        if (mode.equals("wrong-token")) {
            System.out.println("MC_CATALOG wrong-token READY 8080");
        } else if (!mode.equals("initializing")) {
            System.out.println("MC_CATALOG " + token + " READY 12345");
        }
        if (mode.equals("unexpected-exit")) {
            awaitRelease(root);
            System.exit(17); // Test-only unexpected termination.
        }
        try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("STOP " + token)) break;
            }
        }
        if (mode.equals("stalled-cleanup")) awaitRelease(root);
        if (mode.equals("failed-cleanup")) {
            System.out.println("MC_CATALOG " + token + " FAILED SHUTDOWN_FAILED");
            throw new IllegalStateException("Private cleanup diagnostic");
        }
    }

    private static void awaitRelease(Path root) throws Exception {
        try (var changes = root.getFileSystem().newWatchService()) {
            root.register(changes, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Files.exists(root.resolve("release"))) changes.take().reset();
        }
    }
}
