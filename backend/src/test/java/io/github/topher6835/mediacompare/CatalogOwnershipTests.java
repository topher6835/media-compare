package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogOwnershipTests {
    @TempDir Path directory;

    @Test
    void derivesSidecarAndExcludesAnotherOwnerUntilRelease() throws Exception {
        Path database = directory.resolve("nested/catalog.db");
        String url = "jdbc:sqlite:" + database + "?foreign_keys=on";
        try (CatalogOwnership first = CatalogOwnership.acquire(url)) {
            assertEquals(database.getParent().toRealPath().resolve("catalog.db.lock"), first.lockPath());
            assertTrue(Files.exists(first.lockPath()));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> CatalogOwnership.acquire(url));
            assertEquals("Media Compare catalog is already in use by another backend process.", failure.getMessage());
        }
        try (CatalogOwnership later = CatalogOwnership.acquire(url)) {
            assertTrue(Files.exists(later.lockPath()));
        }
    }

    @Test
    void fileUriAndPlainPathReferToTheSameLock() throws Exception {
        Path database = directory.resolve("with spaces.db");
        try (CatalogOwnership first = CatalogOwnership.acquire("jdbc:sqlite:" + database)) {
            assertNotNull(first.lockPath());
            assertThrows(IllegalStateException.class,
                    () -> CatalogOwnership.acquire("jdbc:sqlite:" + database.toUri() + "?foreign_keys=on"));
        }
    }

    @Test
    void aRealSecondJvmCannotAcquireOwnership() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("process.db");
        try (CatalogOwnership first = CatalogOwnership.acquire(url)) {
            assertNotNull(first.lockPath());
            assertThrows(IllegalStateException.class, () -> CatalogOwnership.acquire(url));
            assertEquals(23, runProbe(url));
        }
        assertEquals(0, runProbe(url));
    }

    @Test
    void inMemoryCatalogsNeedNoCrossProcessLockAndUnsupportedLocationsFail() throws Exception {
        try (CatalogOwnership memory = CatalogOwnership.acquire(
                "jdbc:sqlite:file:test?mode=memory&cache=shared")) {
            assertNull(memory.lockPath());
        }
        assertThrows(IllegalArgumentException.class, () -> CatalogOwnership.acquire("jdbc:sqlite:"));
        assertThrows(IllegalArgumentException.class, () -> CatalogOwnership.acquire("jdbc:other:file"));
    }

    private int runProbe(String url) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockProbe.class.getName(), url).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "lock probe timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            try (CatalogOwnership ignored = CatalogOwnership.acquire(args[0])) {
                // OS locking must work across JVMs, not just through overlapping-lock detection.
            } catch (IllegalStateException exception) {
                System.exit(23);
            }
        }
    }
}
