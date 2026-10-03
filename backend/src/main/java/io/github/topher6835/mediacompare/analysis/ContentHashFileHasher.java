package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.filesystem.HostFileCheck;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.scan.IndexingInterruptedException;

import org.springframework.stereotype.Component;

@Component
public class ContentHashFileHasher {

    private static final int BUFFER_SIZE = 64 * 1024;

    public String hash(ContentHashCandidate candidate) throws IOException {
        return hash(candidate, () -> { });
    }

    /** Native reads in a mixed bundle report buffer progress without changing native file authority. */
    public String hash(ContentHashCandidate candidate, Runnable progress) throws IOException {
        HostFileCheck before = inspect(candidate);
        Path file = before.path();

        MessageDigest digest = newDigest();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        long bytesRead = 0;
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            int read;
            while ((read = read(channel, buffer)) != -1) {
                bytesRead += read;
                if (bytesRead > candidate.sizeBytes()) {
                    throw stale(candidate, "file grew during hashing");
                }
                IndexingInterruptedException.check();
                if (read > 0) progress.run();
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }

        if (bytesRead != candidate.sizeBytes()) {
            throw stale(candidate, "file length changed during hashing");
        }
        HostFileCheck after = inspect(candidate);
        if (!before.sameFileAs(after)) {
            throw stale(candidate, "file identity changed during hashing");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Small deterministic seam for interrupted/changing-file read tests. */
    protected int read(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        return channel.read(buffer);
    }

    private static HostFileCheck inspect(ContentHashCandidate candidate) throws IOException {
        try {
            var location = new LocationPathCodec().decode(candidate.locationPath());
            if (!LocationKeyCodec.matches(location, LocationKey.parse(candidate.locationKey()))
                    || location.components().isEmpty()) {
                throw stale(candidate, "absolute location identity is invalid");
            }
            HostFileCheck check = HostFileSystems.current().inspect(location, candidate.sizeBytes(),
                    candidate.modifiedTimeEpochSecond(), candidate.modifiedTimeNano());
            if (check.status() != io.github.topher6835.mediacompare.filesystem.HostFileStatus.ESTABLISHED) {
                throw stale(candidate, "filesystem evidence is " + check.status());
            }
            return check;
        } catch (IllegalArgumentException exception) {
            throw stale(candidate, "absolute location identity is invalid");
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(Sha256AnalysisDefinition.ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static StaleContentHashException stale(ContentHashCandidate candidate, String detail) {
        return new StaleContentHashException(candidate.fileEntryId(), detail);
    }
}
