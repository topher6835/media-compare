package io.github.topher6835.mediacompare.analysis;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class MediaMetadataCache {

    private static final String COMPLETED_STATUS = "COMPLETED";

    private final AnalysisRepository analysisRepository;
    private final MediaMetadataResultCodec resultCodec;

    public MediaMetadataCache(
            AnalysisRepository analysisRepository, MediaMetadataResultCodec resultCodec) {
        this.analysisRepository = analysisRepository;
        this.resultCodec = resultCodec;
    }

    public Optional<MediaMetadataResult> findReusableResult(
            long contentRecordId, MediaMetadataAnalysisDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Optional<AnalysisRecord> stored = analysisRepository.findAnalysisRecordByCacheKey(
                contentRecordId,
                MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                definition.analyzerId(),
                definition.analyzerVersion(),
                definition.configurationVersion(),
                definition.configurationHash());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        AnalysisRecord analysisRecord = stored.orElseThrow();
        if (!COMPLETED_STATUS.equals(analysisRecord.status())) {
            return Optional.empty();
        }
        return Optional.of(resultCodec.read(analysisRecord.resultJson()));
    }
}
