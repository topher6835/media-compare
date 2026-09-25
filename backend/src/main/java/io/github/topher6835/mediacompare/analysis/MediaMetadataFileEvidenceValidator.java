package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Objects;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;

import org.springframework.stereotype.Component;

@Component
public class MediaMetadataFileEvidenceValidator {

    public Path validateBeforeExtraction(MediaMetadataFileCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try {
            Path file = resolveAbsoluteLocation(candidate);
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
            Path expectedFile = resolveAbsoluteLocation(candidate);
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

    private static Path resolveAbsoluteLocation(MediaMetadataFileCandidate candidate)
            throws IOException {
        final LocationPath location;
        try {
            location = new LocationPathCodec().decode(candidate.locationPath());
            if (!LocationKeyCodec.matches(location, LocationKey.parse(candidate.locationKey()))
                    || location.dialect() != LocationDialect.UNIX || location.components().isEmpty()) {
                throw stale(candidate, "absolute location identity is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw stale(candidate, "absolute location identity is invalid");
        }
        Path resolved = Path.of("/");
        requireDirectoryWithoutLinks(resolved, candidate);
        for (int index = 0; index < location.components().size(); index++) {
            resolved = resolved.resolve(location.components().get(index));
            if (index < location.components().size() - 1) {
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
