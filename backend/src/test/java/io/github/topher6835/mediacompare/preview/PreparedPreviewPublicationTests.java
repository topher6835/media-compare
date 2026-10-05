package io.github.topher6835.mediacompare.preview;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** File-publication checks; real guarded SQLite/authority ordering is covered by Slice 5 flow tests. */
class PreparedPreviewPublicationTests {
    @TempDir Path directory;
    PreviewCacheWriter cache;
    PreviewAsset asset;
    Path temporary, target;

    @BeforeEach void prepare() throws Exception {
        Path root = directory.resolve("cache");
        cache = new PreviewCacheWriter(root.toString(), new PreviewCacheResolver(root.toString()));
        var evidence = PreviewTestFixtures.evidence();
        var definition = SmallThumbnailDefinition.definition();
        String key = PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL, definition);
        String relative = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key, "png");
        temporary = cache.createTemporary(relative, key);
        assertTrue(ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", temporary.toFile()));
        asset = new PreviewAsset(null, key, evidence, PreviewKind.SMALL_THUMBNAIL, definition,
                relative, "image/png", 120, 80, Files.size(temporary), 1);
        target = root.resolve(relative);
    }

    @Test void unchangedOutputPublishesWithTheHostFileKeyBehavior() throws Exception {
        var before = Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var prepared = cache.preparePublication(temporary, asset);
        var after = Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(before.fileKey(), after.fileKey()); // May both be null on Windows Java 21.
        assertEquals(before.size(), after.size());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(temporary))), prepared.validatedSha256());
        assertTrue(install(prepared));
        assertEquals(-1, Files.mismatch(temporary, target));
        assertThrows(IllegalStateException.class, () -> install(prepared));
    }

    @Test void validationPrecedesWriterAndInstallationRequiresWriter() throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        assertThrows(IllegalStateException.class, prepared::install);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> cache.preparePublication(temporary, asset)); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertFalse(Files.exists(target));
    }

    @Test void sizeChangeFailsEvenWithRestoredModificationTime() throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        var time = Files.getLastModifiedTime(temporary);
        Files.write(temporary, new byte[] {1});
        Files.setLastModifiedTime(temporary, time);
        assertThrows(IOException.class, () -> install(prepared));
        assertFalse(Files.exists(target));
    }

    @Test void sameSizeContentMutationFails() throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        mutateSameSize(temporary);
        assertThrows(IOException.class, () -> install(prepared));
        assertFalse(Files.exists(target));
    }

    @Test void replacementFailsEvenWithEquivalentBytesSizeAndTime() throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        replace(temporary);
        assertThrows(IOException.class, () -> install(prepared));
        assertFalse(Files.exists(target));
    }

    @Test void directoryInPlaceOfTemporaryFails() throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        Files.delete(temporary);
        Files.createDirectory(temporary);
        assertThrows(IOException.class, () -> install(prepared));
        assertFalse(Files.exists(target));
    }

    @Test void alreadyValidatedEquivalentTargetRemainsReusable() throws Exception {
        Files.copy(temporary, target);
        var time = Files.getLastModifiedTime(target);
        var prepared = cache.preparePublication(temporary, asset);
        assertFalse(install(prepared));
        assertEquals(time, Files.getLastModifiedTime(target));
        assertEquals(-1, Files.mismatch(temporary, target));
    }

    @ParameterizedTest @ValueSource(strings = {"mutation", "replacement", "missing"})
    void validatedOccupiedTargetCannotChange(String change) throws Exception {
        Files.copy(temporary, target);
        var prepared = cache.preparePublication(temporary, asset);
        switch (change) {
            case "mutation" -> mutateSameSize(target);
            case "replacement" -> replace(target);
            case "missing" -> Files.delete(target);
        }
        assertThrows(IOException.class, () -> install(prepared));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void racingUnvalidatedTargetFailsClosedEvenWhenEquivalent(boolean equivalent) throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        if (equivalent) Files.copy(temporary, target);
        else Files.write(target, new byte[] {1, 2, 3});
        var raced = Files.readAllBytes(target);
        assertThrows(FileAlreadyExistsException.class, () -> install(prepared));
        assertArrayEquals(raced, Files.readAllBytes(target));
    }

    @Test void corruptOccupiedTargetFailsBeforeWriter() throws Exception {
        Files.write(target, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> cache.preparePublication(temporary, asset));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
    }

    @Test void parentReplacementFailsEvenWithEquivalentTemporary() throws Exception {
        var prepared = cache.preparePublication(temporary, asset);
        Path parent = target.getParent();
        Path retained = parent.resolveSibling("retained-parent");
        Files.move(parent, retained);
        Files.createDirectory(parent);
        // Preserve the exact staged file; only the parent identity changes.
        Files.move(retained.resolve(temporary.getFileName()), temporary);
        assertThrows(IOException.class, () -> install(prepared));
        assertFalse(Files.exists(target));
    }

    private static void mutateSameSize(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        var time = Files.getLastModifiedTime(path);
        bytes[bytes.length - 1] ^= 1;
        Files.write(path, bytes);
        // Avoid depending on clock/filesystem timestamp granularity in this mutation test.
        Files.setLastModifiedTime(path, FileTime.fromMillis(time.toMillis() + 10_000));
    }

    private static void replace(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        var time = Files.getLastModifiedTime(path);
        // Keep the old inode/file ID alive so this proves replacement independently of ID reuse.
        Files.move(path, path.resolveSibling(path.getFileName() + ".retained"));
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, time);
    }

    private static boolean install(PreviewCacheWriter.PreparedPublication prepared) throws IOException {
        // Exercise the file checks in isolation; this flag grants no actual content-read authority.
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { return prepared.install(); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
