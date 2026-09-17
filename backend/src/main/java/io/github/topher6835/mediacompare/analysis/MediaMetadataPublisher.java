package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MediaMetadataPublisher {

    private static final String COMPLETED_STATUS = "COMPLETED";

    private final MediaMetadataCandidateRepository candidateRepository;
    private final AnalysisRepository analysisRepository;
    private final MediaMetadataResultCodec resultCodec;

    public MediaMetadataPublisher(
            MediaMetadataCandidateRepository candidateRepository,
            AnalysisRepository analysisRepository,
            MediaMetadataResultCodec resultCodec) {
        this.candidateRepository = candidateRepository;
        this.analysisRepository = analysisRepository;
        this.resultCodec = resultCodec;
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

        if (candidateRepository.verifyCandidate(candidate) != 1) {
            throw new StaleMediaMetadataEvidenceException(
                    candidate.fileEntryId(), "catalog evidence changed");
        }

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
}
