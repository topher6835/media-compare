package io.github.topher6835.mediacompare.analysis;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class ImageIoMediaMetadataAnalyzer {

    static final int OCCURRENCE_PAGE_SIZE = 100;

    private final MediaMetadataCandidateRepository candidateRepository;
    private final MediaMetadataCache metadataCache;
    private final MediaMetadataFileEvidenceValidator evidenceValidator;
    private final ImageMetadataExtractor imageMetadataExtractor;
    private final MediaMetadataPublisher publisher;

    public ImageIoMediaMetadataAnalyzer(
            MediaMetadataCandidateRepository candidateRepository,
            MediaMetadataCache metadataCache,
            MediaMetadataFileEvidenceValidator evidenceValidator,
            ImageMetadataExtractor imageMetadataExtractor,
            MediaMetadataPublisher publisher) {
        this.candidateRepository = candidateRepository;
        this.metadataCache = metadataCache;
        this.evidenceValidator = evidenceValidator;
        this.imageMetadataExtractor = imageMetadataExtractor;
        this.publisher = publisher;
    }

    public Optional<MediaMetadataResult> analyze(MediaMetadataContentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        MediaMetadataAnalysisDefinition definition = ImageIoMediaMetadataDefinition.definition();
        Optional<MediaMetadataResult> cached = metadataCache.findReusableResult(
                candidate.contentRecordId(), definition);
        if (cached.isPresent()) {
            return cached;
        }

        long afterFileEntryId = 0;
        while (true) {
            List<MediaMetadataFileCandidate> occurrences = candidateRepository.findOccurrences(
                    candidate, afterFileEntryId, OCCURRENCE_PAGE_SIZE);
            if (occurrences.isEmpty()) {
                return Optional.empty();
            }

            for (MediaMetadataFileCandidate occurrence : occurrences) {
                afterFileEntryId = occurrence.fileEntryId();
                try {
                    long startedAtMs = System.currentTimeMillis();
                    Path file = evidenceValidator.validateBeforeExtraction(occurrence);
                    MediaMetadataResult result;
                    try {
                        result = imageMetadataExtractor.extract(file);
                    } catch (ImageMetadataExtractionException exception) {
                        evidenceValidator.validateAfterExtraction(occurrence, file);
                        throw new CurrentImageMetadataExtractionException(
                                occurrence, startedAtMs, exception);
                    }
                    evidenceValidator.validateAfterExtraction(occurrence, file);
                    publisher.publishIfStillCurrent(
                            occurrence, definition, result, startedAtMs, System.currentTimeMillis());
                    return Optional.of(result);
                } catch (StaleMediaMetadataEvidenceException exception) {
                    // Another current occurrence can still safely represent this ContentRecord.
                }
            }
        }
    }
}
