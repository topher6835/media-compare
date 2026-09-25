package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.AvailableMediaMetadata;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.analysis.ImageMediaMetadata;
import io.github.topher6835.mediacompare.analysis.MediaKind;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResult;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MediaMetadataPersistenceTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private MediaMetadataResultCodec metadataResultCodec;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
        jdbcTemplate.update("DELETE FROM source_membership");
        jdbcTemplate.update("DELETE FROM file_entry");
        jdbcTemplate.update("DELETE FROM scan_run_source");
        jdbcTemplate.update("DELETE FROM working_set_content");
        jdbcTemplate.update("DELETE FROM analysis_record");
        jdbcTemplate.update("DELETE FROM job");
        jdbcTemplate.update("DELETE FROM scan_run");
        jdbcTemplate.update("DELETE FROM working_set");
        jdbcTemplate.update("DELETE FROM content_record");
        jdbcTemplate.update("DELETE FROM source");
    }

    @Test
    void persistsTypedMediaMetadataWhileExactHashArtifactsKeepNullResults() {
        ContentRecord exactContent = catalogRepository.insert(new ContentRecord(null, 10, 1));
        AnalysisRecord exact = analysisRepository.insert(new AnalysisRecord(
                null, exactContent.id(), "CONTENT_HASH", "builtin.sha256", "1", 1,
                "hash-config", "{}", null, "COMPLETED", 1, 1, 1L, 2L, null));
        analysisRepository.insert(new ContentHash(exact.id(), "SHA-256", "abc123"));

        MediaMetadataResult expected = new AvailableMediaMetadata(
                1, MediaKind.IMAGE, new ImageMediaMetadata("png", 800, 600), null);
        ContentRecord metadataContent = catalogRepository.insert(new ContentRecord(null, 20, 1));
        AnalysisRecord metadata = analysisRepository.insert(new AnalysisRecord(
                null, metadataContent.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                "test.media-metadata", "1", 1, "metadata-config", "{}",
                metadataResultCodec.write(expected), "COMPLETED", 1, 3, 3L, 4L, null));

        AnalysisRecord loadedExact = analysisRepository.findAnalysisRecordById(exact.id()).orElseThrow();
        AnalysisRecord loadedMetadata = analysisRepository.findAnalysisRecordById(metadata.id()).orElseThrow();

        assertNull(loadedExact.resultJson());
        assertEquals(exact, loadedExact);
        assertEquals(metadata, loadedMetadata);
        assertEquals(expected, metadataResultCodec.read(loadedMetadata.resultJson()));
        assertEquals("abc123", analysisRepository.findContentHash(exact.id()).orElseThrow().digestHex());
    }
}
