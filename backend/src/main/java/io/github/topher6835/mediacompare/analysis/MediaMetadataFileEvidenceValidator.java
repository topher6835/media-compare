package io.github.topher6835.mediacompare.analysis;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import io.github.topher6835.mediacompare.filesystem.HostFileCheck;
import io.github.topher6835.mediacompare.filesystem.HostFileStatus;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.location.LocationKey;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;

import org.springframework.stereotype.Component;

@Component
public class MediaMetadataFileEvidenceValidator {

    public Path validateBeforeExtraction(MediaMetadataFileCandidate candidate) {
        return captureBeforeExtraction(candidate).path();
    }

    /** Retains an operation-local identity token for the post-read check. */
    public HostFileCheck captureBeforeExtraction(MediaMetadataFileCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try {
            return inspect(candidate);
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
            if (!inspect(candidate).path().equals(file)) {
                throw stale(candidate, "validated path changed during extraction");
            }
        } catch (StaleMediaMetadataEvidenceException exception) {
            throw exception;
        } catch (IOException | InvalidPathException | SecurityException | UnsupportedOperationException exception) {
            throw stale(candidate, "filesystem evidence could not be validated after extraction", exception);
        }
    }

    public void validateAfterExtraction(MediaMetadataFileCandidate candidate, HostFileCheck before) {
        Objects.requireNonNull(before, "before");
        try {
            if (!before.sameFileAs(inspect(candidate))) {
                throw stale(candidate, "file identity changed during extraction");
            }
        } catch (StaleMediaMetadataEvidenceException exception) {
            throw exception;
        } catch (IOException | InvalidPathException | SecurityException | UnsupportedOperationException exception) {
            throw stale(candidate, "filesystem evidence could not be validated after extraction", exception);
        }
    }

    private static HostFileCheck inspect(MediaMetadataFileCandidate candidate) throws IOException {
        try {
            var location = new LocationPathCodec().decode(candidate.locationPath());
            if (!LocationKeyCodec.matches(location, LocationKey.parse(candidate.locationKey()))
                    || location.components().isEmpty()) {
                throw stale(candidate, "absolute location identity is invalid");
            }
            HostFileCheck check = HostFileSystems.current().inspect(location, candidate.expectedSizeBytes(),
                    candidate.expectedModifiedTimeEpochSecond(), candidate.expectedModifiedTimeNano());
            if (check.status() != HostFileStatus.ESTABLISHED) {
                throw stale(candidate, "filesystem evidence is " + check.status());
            }
            return check;
        } catch (IllegalArgumentException exception) {
            throw stale(candidate, "absolute location identity is invalid");
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
