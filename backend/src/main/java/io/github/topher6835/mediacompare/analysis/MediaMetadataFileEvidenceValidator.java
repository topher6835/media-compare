package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class MediaMetadataFileEvidenceValidator {

    public Path validateBeforeExtraction(MediaMetadataFileCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try {
            Path file = resolvePortableRelativePath(Path.of(candidate.sourceRootPath()), candidate);
            requireExpectedRegularFile(candidate, file, "filesystem evidence changed before extraction");
            return file;
        } catch (StaleMediaMetadataEvidenceException exception) {
            throw exception;
        } catch (IOException | InvalidPathException | SecurityException | UnsupportedOperationException exception) {
            throw stale(candidate, "filesystem evidence could not be validated before extraction", exception);
        }
    }

    public void validateAfterExtraction(MediaMetadataFileCandidate candidate, Path file) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(file, "file");
        try {
            Path expectedFile = resolvePortableRelativePath(Path.of(candidate.sourceRootPath()), candidate);
            if (!expectedFile.equals(file)) {
                throw stale(candidate, "validated path changed during extraction");
            }
            requireExpectedRegularFile(candidate, file, "filesystem evidence changed during extraction");
        } catch (StaleMediaMetadataEvidenceException exception) {
            throw exception;
        } catch (IOException | InvalidPathException | SecurityException | UnsupportedOperationException exception) {
            throw stale(candidate, "filesystem evidence could not be validated after extraction", exception);
        }
    }

    private static Path resolvePortableRelativePath(Path sourceRoot, MediaMetadataFileCandidate candidate)
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
            Path directory, MediaMetadataFileCandidate candidate) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw stale(candidate, "path contains a non-directory or symbolic link");
        }
    }

    private static void requireExpectedRegularFile(
            MediaMetadataFileCandidate candidate, Path file, String detail) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw stale(candidate, "candidate is not a regular non-symbolic-link file");
        }

        Instant modifiedTime = attributes.lastModifiedTime().toInstant();
        if (attributes.size() != candidate.expectedSizeBytes()
                || candidate.expectedModifiedTimeEpochSecond() == null
                || candidate.expectedModifiedTimeNano() == null
                || modifiedTime.getEpochSecond() != candidate.expectedModifiedTimeEpochSecond()
                || modifiedTime.getNano() != candidate.expectedModifiedTimeNano()) {
            throw stale(candidate, detail);
        }
    }

    private static StaleMediaMetadataEvidenceException stale(
            MediaMetadataFileCandidate candidate, String detail) {
        return new StaleMediaMetadataEvidenceException(candidate.fileEntryId(), detail);
    }

    private static StaleMediaMetadataEvidenceException stale(
            MediaMetadataFileCandidate candidate, String detail, Throwable cause) {
        return new StaleMediaMetadataEvidenceException(candidate.fileEntryId(), detail, cause);
    }
}
