package io.github.topher6835.mediacompare.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.filesystem.CheckoutTempDirFactory;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;

@EnabledOnOs({OS.WINDOWS, OS.MAC})
class ContentHashFileHasherTests {
    @TempDir(factory = CheckoutTempDirFactory.class) Path directory;

    @Test
    void reportsProgressOnlyForCurrentPositiveReadAndPreservesSha() throws Exception {
        var candidate = candidate();
        var callbacks = new AtomicInteger();
        var callbacksBeforeRead = new ArrayList<Integer>();
        var reads = new ArrayList<Integer>();
        var hasher = new ContentHashFileHasher() {
            @Override protected int read(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
                callbacksBeforeRead.add(callbacks.get());
                int read;
                if (reads.size() == 1) {
                    read = 0;
                } else {
                    if (reads.isEmpty()) buffer.limit(1);
                    read = super.read(channel, buffer);
                }
                reads.add(read);
                return read;
            }
        };

        String sha = hasher.hash(candidate, callbacks::incrementAndGet);

        assertEquals(List.of(1, 0, 2, -1), reads);
        assertEquals(List.of(0, 1, 1, 2), callbacksBeforeRead);
        assertEquals(2, callbacks.get(), "EOF must not report progress");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha);
        assertEquals(new ContentHashFileHasher().hash(candidate), sha);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void stillRejectsReadByteCountDifferentFromCandidateSize(int byteCount) throws Exception {
        var candidate = candidate();
        var hasher = new ContentHashFileHasher() {
            boolean first = true;
            @Override protected int read(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
                if (!first) return -1;
                first = false;
                if (byteCount == 2) buffer.limit(2);
                int read = super.read(channel, buffer);
                if (byteCount == 4) {
                    buffer.put((byte) 'd');
                    read++;
                }
                return read;
            }
        };

        var exception = assertThrows(StaleContentHashException.class, () -> hasher.hash(candidate, () -> { }));
        assertTrue(exception.getMessage().contains(byteCount == 2
                ? "file length changed during hashing" : "file grew during hashing"));
    }

    private ContentHashCandidate candidate() throws IOException {
        Path file = Files.writeString(directory.resolve("file.txt"), "abc");
        var time = Files.getLastModifiedTime(file).toInstant();
        var dialect = HostFileSystems.isWindows() ? LocationDialect.WINDOWS_DRIVE : LocationDialect.UNIX;
        var location = LocationPathParser.parse(dialect, file.toString());
        return new ContentHashCandidate(1, 1, 1, 1, UUID.randomUUID().toString(), 1, 0,
                new LocationPathCodec().encode(location), LocationKeyCodec.encode(location).value(),
                0, Files.size(file), time.getEpochSecond(), time.getNano(), 1);
    }
}
