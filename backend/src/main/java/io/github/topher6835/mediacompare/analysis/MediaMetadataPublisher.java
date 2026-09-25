package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;
import java.util.Optional;
import io.github.topher6835.mediacompare.catalog.CurrentMembershipAuthority;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MediaMetadataPublisher {

    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String FAILED_STATUS = "FAILED";

    private final MediaMetadataCandidateRepository candidateRepository;
    private final AnalysisRepository analysisRepository;
    private final MediaMetadataResultCodec resultCodec;
    private final CurrentMembershipAuthority authority;

    public MediaMetadataPublisher(
            MediaMetadataCandidateRepository candidateRepository,
            AnalysisRepository analysisRepository,
            MediaMetadataResultCodec resultCodec,
            CurrentMembershipAuthority authority) {
        this.candidateRepository = candidateRepository;
        this.analysisRepository = analysisRepository;
        this.resultCodec = resultCodec;
        this.authority = authority;
    }

    @Transactional
    public AnalysisRecord publishIfStillCurrent(
            MediaMetadataFileCandidate candidate,
            MediaMetadataAnalysisDefinition definition,
            MediaMetadataResult result,
            long startedAtMs,
            long finishedAtMs) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(definition, "definition");
        String resultJson = resultCodec.write(Objects.requireNonNull(result, "result"));

        authority.reserveAndRequire(candidate.sourceId(), candidate.sourceLocationRevision(),
                candidate.contextId(), candidate.contextRevision(), candidate.fileEntryId(),
                candidate.membershipId());

        if (candidateRepository.verifyCandidate(candidate) != 1) {
            throw new StaleMediaMetadataEvidenceException(
                    candidate.fileEntryId(), "catalog evidence changed");
        }

        Optional<AnalysisRecord> existing = findCompatible(candidate, definition);
        if (existing.isEmpty()) {
            return analysisRepository.insert(new AnalysisRecord(
                    null,
                    candidate.contentRecordId(),
                    MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                    definition.analyzerId(),
                    definition.analyzerVersion(),
                    definition.configurationVersion(),
                    definition.configurationHash(),
                    definition.configurationJson(),
                    resultJson,
                    COMPLETED_STATUS,
                    1,
                    startedAtMs,
                    startedAtMs,
                    finishedAtMs,
                    null));
        }

        AnalysisRecord stored = existing.orElseThrow();
        if (COMPLETED_STATUS.equals(stored.status())) {
            return stored;
        }
        if (!FAILED_STATUS.equals(stored.status())
                || analysisRepository.completeFailedAnalysisRecord(
                        stored.id(), resultJson, startedAtMs, finishedAtMs) != 1) {
            throw new IllegalStateException("Compatible media metadata analysis is already in progress");
        }
        return analysisRepository.findAnalysisRecordById(stored.id()).orElseThrow();
    }

    @Transactional
    public AnalysisRecord publishFailureIfStillCurrent(
            MediaMetadataFileCandidate candidate,
            MediaMetadataAnalysisDefinition definition,
            long startedAtMs,
            long finishedAtMs,
            String errorMessage) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(definition, "definition");
        String safeError = requireSafeError(errorMessage);

        authority.reserveAndRequire(candidate.sourceId(), candidate.sourceLocationRevision(),
                candidate.contextId(), candidate.contextRevision(), candidate.fileEntryId(),
                candidate.membershipId());

        if (candidateRepository.verifyCandidate(candidate) != 1) {
            throw new StaleMediaMetadataEvidenceException(
                    candidate.fileEntryId(), "catalog evidence changed");
        }

        Optional<AnalysisRecord> existing = findCompatible(candidate, definition);
        if (existing.isEmpty()) {
            return analysisRepository.insert(new AnalysisRecord(
                    null,
                    candidate.contentRecordId(),
                    MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                    definition.analyzerId(),
                    definition.analyzerVersion(),
                    definition.configurationVersion(),
                    definition.configurationHash(),
                    definition.configurationJson(),
                    null,
                    FAILED_STATUS,
                    1,
                    startedAtMs,
                    startedAtMs,
                    finishedAtMs,
                    safeError));
        }

        AnalysisRecord stored = existing.orElseThrow();
        if (COMPLETED_STATUS.equals(stored.status())) {
            return stored;
        }
        if (!FAILED_STATUS.equals(stored.status())
                || analysisRepository.failAgain(
                        stored.id(), startedAtMs, finishedAtMs, safeError) != 1) {
            throw new IllegalStateException("Compatible media metadata analysis is already in progress");
        }
        return analysisRepository.findAnalysisRecordById(stored.id()).orElseThrow();
    }

    private Optional<AnalysisRecord> findCompatible(
            MediaMetadataFileCandidate candidate, MediaMetadataAnalysisDefinition definition) {
        return analysisRepository.findAnalysisRecordByCacheKey(
                candidate.contentRecordId(),
                MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(),
                definition.analyzerVersion(),
                definition.configurationVersion(),
                definition.configurationHash());
    }

    private static String requireSafeError(String errorMessage) {
        String value = Objects.requireNonNull(errorMessage, "errorMessage");
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("errorMessage must contain 1 to 200 characters");
        }
        return value;
    }
}
