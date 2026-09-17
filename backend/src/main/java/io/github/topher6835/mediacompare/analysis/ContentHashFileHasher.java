package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import io.github.topher6835.mediacompare.catalog.ContentHashCandidate;
import io.github.topher6835.mediacompare.scan.IndexingInterruptedException;

import org.springframework.stereotype.Component;

@Component
public class ContentHashFileHasher {

    private static final int BUFFER_SIZE = 64 * 1024;

    public String hash(Path sourceRoot, ContentHashCandidate candidate) throws IOException {
        Path file = resolvePortableRelativePath(sourceRoot, candidate);
        BasicFileAttributes before = readRegularFileAttributes(file, candidate);
        requireExpectedMetadata(candidate, before, "metadata changed before hashing");

        MessageDigest digest = newDigest();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            while (channel.read(buffer) != -1) {
                IndexingInterruptedException.check();
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }

        BasicFileAttributes after = readRegularFileAttributes(file, candidate);
        requireExpectedMetadata(candidate, after, "metadata changed during hashing");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path resolvePortableRelativePath(Path sourceRoot, ContentHashCandidate candidate)
            throws IOException {
        String relativePath = candidate.relativePath();
        if (relativePath == null || relativePath.isEmpty()) {
            throw stale(candidate, "relative path is empty");
        }

        requireDirectoryWithoutLinks(sourceRoot, candidate);
        Path resolved = sourceRoot;
        String[] segments = relativePath.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw stale(candidate, "relative path contains an unsafe component");
            }
            Path component = Path.of(segment);
            if (component.isAbsolute() || component.getRoot() != null || component.getNameCount() != 1) {
                throw stale(candidate, "relative path contains an unsafe component");
            }
            resolved = resolved.resolve(component);
            if (index < segments.length - 1) {
                requireDirectoryWithoutLinks(resolved, candidate);
            }
        }
        return resolved;
    }

    private static void requireDirectoryWithoutLinks(
            Path directory, ContentHashCandidate candidate) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw stale(candidate, "path contains a non-directory or symbolic link");
        }
    }

    private static BasicFileAttributes readRegularFileAttributes(
            Path file, ContentHashCandidate candidate) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw stale(candidate, "candidate is not a regular non-symbolic-link file");
        }
        return attributes;
    }

    private static void requireExpectedMetadata(
            ContentHashCandidate candidate, BasicFileAttributes attributes, String detail) {
        Instant modifiedTime = attributes.lastModifiedTime().toInstant();
        if (attributes.size() != candidate.sizeBytes()
                || candidate.modifiedTimeEpochSecond() == null
                || candidate.modifiedTimeNano() == null
                || modifiedTime.getEpochSecond() != candidate.modifiedTimeEpochSecond()
                || modifiedTime.getNano() != candidate.modifiedTimeNano()) {
            throw stale(candidate, detail);
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
