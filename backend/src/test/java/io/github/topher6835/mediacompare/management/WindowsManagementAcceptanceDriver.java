package io.github.topher6835.mediacompare.management;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import io.github.topher6835.mediacompare.config.CatalogOwnership;

/** Test-only controller loaded beside the packaged application for native Windows browser acceptance. */
public final class WindowsManagementAcceptanceDriver {
    private WindowsManagementAcceptanceDriver() { }

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            throw new IllegalStateException("Windows acceptance only");
        }
        if (args[0].equals("manage")) {
            var volume = new io.github.topher6835.mediacompare.filesystem.WindowsVolumeProbe()
                    .probe(Path.of("").toAbsolutePath());
            if (!"NTFS".equals(volume.nioType()) || volume.nativeVolume().driveType() != 3) {
                throw new IllegalStateException("Acceptance fixtures require fixed local NTFS");
            }
            System.out.println("Acceptance native fixture volume=" + volume);
            ManagementApplication.main(Arrays.copyOfRange(args, 1, args.length));
            System.out.println("Acceptance manager pid=" + ProcessHandle.current().pid());
            while (System.in.read() != -1) { /* EOF is the test's ordinary shutdown request. */ }
            // Normal JVM shutdown invokes the unmodified ManagementApplication shutdown hook.
            System.exit(0);
        } else if (args[0].equals("lock")) {
            Path root = Path.of(CatalogChildCommand.sessionPath(args[1]));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.isRegularFile(root.resolve("session.json"))) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Manifest not published");
                Thread.sleep(1);
            }
            try (var ownership = CatalogOwnership.acquire("jdbc:sqlite:" + root.resolve("catalog.db").toUri())) {
                if (ownership.lockPath() == null) throw new IllegalStateException("Missing native lock");
                System.out.println("LOCKED pid=" + ProcessHandle.current().pid());
                while (System.in.read() != -1) { /* Release only this test's own lock on EOF. */ }
            }
        } else {
            throw new IllegalArgumentException("Expected manage or lock");
        }
    }
}
