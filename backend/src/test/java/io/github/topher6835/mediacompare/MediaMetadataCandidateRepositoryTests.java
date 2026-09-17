package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.analysis.ImageMediaMetadata;
import io.github.topher6835.mediacompare.analysis.MediaKind;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCache;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataContentCandidate;
import io.github.topher6835.mediacompare.analysis.MediaMetadataFileCandidate;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResult;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResultCodec;
import io.github.topher6835.mediacompare.analysis.AvailableMediaMetadata;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class MediaMetadataCandidateRepositoryTests {

    private static final MediaMetadataAnalysisDefinition DEFINITION =
            new MediaMetadataAnalysisDefinition("test.image", "1", 1, "config-hash", "{}");
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private MediaMetadataCandidateRepository candidateRepository;

    @Autowired
    private MediaMetadataCache metadataCache;

    @Autowired
    private MediaMetadataResultCodec resultCodec;

    @BeforeEach
    void clearApplicationTables() {
        jdbcTemplate.update("DELETE FROM content_hash");
        jdbcTemplate.update("DELETE FROM job_stage");
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
    void selectsOneContentRecordWithOnePresentOccurrence() {
        Source source = insertSource("/catalog/single");
        ContentRecord content = insertContent(10);
        FileEntry occurrence = insertFile(source, content, "single.jpg", "PRESENT");

        List<MediaMetadataContentCandidate> candidates =
                candidateRepository.findCandidates(DEFINITION, 0, 10);
        List<MediaMetadataFileCandidate> occurrences =
                candidateRepository.findOccurrences(candidates.getFirst(), 0, 10);

        assertEquals(List.of(new MediaMetadataContentCandidate(content.id(), 10)), candidates);
        assertEquals(List.of(occurrence.id()),
                occurrences.stream().map(MediaMetadataFileCandidate::fileEntryId).toList());
    }

    @Test
    void deduplicatesContentCandidatesAndOrdersPresentOccurrencesByFileEntryId() {
        Source source = insertSource("/catalog/source");
        ContentRecord content = insertContent(10);
        FileEntry first = insertFile(source, content, "first.jpg", "PRESENT");
        FileEntry second = insertFile(source, content, "second.jpg", "PRESENT");

        List<MediaMetadataContentCandidate> candidates =
                candidateRepository.findCandidates(DEFINITION, 0, 10);
        List<MediaMetadataFileCandidate> occurrences =
                candidateRepository.findOccurrences(candidates.getFirst(), 0, 10);

        assertEquals(List.of(new MediaMetadataContentCandidate(content.id(), 10)), candidates);
        assertEquals(List.of(first.id(), second.id()),
                occurrences.stream().map(MediaMetadataFileCandidate::fileEntryId).toList());
        assertEquals(content.id(), occurrences.getFirst().contentRecordId());
        assertEquals(source.rootPath(), occurrences.getFirst().sourceRootPath());
    }

    @Test
    void missingOnlyContentIsNotANewExtractionCandidate() {
        Source source = insertSource("/catalog/missing");
        ContentRecord content = insertContent(10);
        insertFile(source, content, "missing.jpg", "MISSING");

        assertTrue(candidateRepository.findCandidates(DEFINITION, 0, 10).isEmpty());
    }

    @Test
    void compatibleCompletedResultIsReusableWithoutAnyPresentOccurrence() {
        Source source = insertSource("/catalog/cached");
        ContentRecord content = insertContent(10);
        insertFile(source, content, "cached.jpg", "PRESENT");
        MediaMetadataResult expected = imageResult();
        insertCompletedMetadata(content, expected);

        assertTrue(candidateRepository.findCandidates(DEFINITION, 0, 10).isEmpty());

        jdbcTemplate.update("UPDATE file_entry SET presence_status = 'MISSING' WHERE current_content_id = ?",
                content.id());
        assertEquals(expected, metadataCache.findReusableResult(content.id(), DEFINITION).orElseThrow());
        assertTrue(candidateRepository.findCandidates(DEFINITION, 0, 10).isEmpty());
    }

    @Test
    void compatibleNoncompletedRecordIsBlockedRatherThanRescheduledOrReused() {
        Source source = insertSource("/catalog/running");
        ContentRecord content = insertContent(10);
        insertFile(source, content, "running.jpg", "PRESENT");
        analysisRepository.insert(new AnalysisRecord(
                null, content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                DEFINITION.analyzerId(), DEFINITION.analyzerVersion(),
                DEFINITION.configurationVersion(), DEFINITION.configurationHash(),
                DEFINITION.configurationJson(), null,
                "RUNNING", 1, 1, 1L, null, null));

        assertTrue(candidateRepository.findCandidates(DEFINITION, 0, 10).isEmpty());
        assertTrue(metadataCache.findReusableResult(content.id(), DEFINITION).isEmpty());
    }

    @Test
    void equalExactHashesDoNotMergeMetadataCandidates() {
        Source source = insertSource("/catalog/equal-hash");
        ContentRecord first = insertContent(10);
        ContentRecord second = insertContent(10);
        insertFile(source, first, "first.jpg", "PRESENT");
        insertFile(source, second, "second.jpg", "PRESENT");
        insertCompletedHash(first);
        insertCompletedHash(second);

        assertEquals(List.of(first.id(), second.id()), candidateRepository.findCandidates(DEFINITION, 0, 10)
                .stream().map(MediaMetadataContentCandidate::contentRecordId).toList());
    }

    @Test
    void contentAndOccurrenceEnumerationUseIndependentKeysetBounds() {
        Source source = insertSource("/catalog/paging");
        ContentRecord first = insertContent(1);
        ContentRecord second = insertContent(2);
        ContentRecord third = insertContent(3);
        insertFile(source, first, "first-a.jpg", "PRESENT");
        insertFile(source, first, "first-b.jpg", "PRESENT");
        insertFile(source, first, "first-c.jpg", "PRESENT");
        insertFile(source, second, "second.jpg", "PRESENT");
        insertFile(source, third, "third.jpg", "PRESENT");

        List<MediaMetadataContentCandidate> firstPage =
                candidateRepository.findCandidates(DEFINITION, 0, 2);
        List<MediaMetadataContentCandidate> secondPage = candidateRepository.findCandidates(
                DEFINITION, firstPage.getLast().contentRecordId(), 2);
        List<MediaMetadataFileCandidate> firstOccurrencePage =
                candidateRepository.findOccurrences(firstPage.getFirst(), 0, 2);
        List<MediaMetadataFileCandidate> secondOccurrencePage = candidateRepository.findOccurrences(
                firstPage.getFirst(), firstOccurrencePage.getLast().fileEntryId(), 2);

        assertEquals(List.of(first.id(), second.id()),
                firstPage.stream().map(MediaMetadataContentCandidate::contentRecordId).toList());
        assertEquals(List.of(third.id()),
                secondPage.stream().map(MediaMetadataContentCandidate::contentRecordId).toList());
        assertEquals(2, firstOccurrencePage.size());
        assertEquals(1, secondOccurrencePage.size());
        assertTrue(firstOccurrencePage.getLast().fileEntryId()
                < secondOccurrencePage.getFirst().fileEntryId());
    }

    @Test
    void missingEarlierOccurrenceDoesNotHideLaterPresentOccurrence() {
        Source source = insertSource("/catalog/fallback");
        ContentRecord content = insertContent(10);
        insertFile(source, content, "first.jpg", "MISSING");
        FileEntry second = insertFile(source, content, "second.jpg", "PRESENT");

        MediaMetadataContentCandidate candidate =
                candidateRepository.findCandidates(DEFINITION, 0, 10).getFirst();
        List<MediaMetadataFileCandidate> occurrences =
                candidateRepository.findOccurrences(candidate, 0, 10);

        assertEquals(List.of(second.id()),
                occurrences.stream().map(MediaMetadataFileCandidate::fileEntryId).toList());
    }

    private Source insertSource(String rootPath) {
        return catalogRepository.insert(new Source(null, rootPath, rootPath, rootPath, 0, 1, 1));
    }

    private ContentRecord insertContent(long sizeBytes) {
        return catalogRepository.insert(new ContentRecord(null, sizeBytes, 1));
    }

    private FileEntry insertFile(
            Source source, ContentRecord content, String relativePath, String presenceStatus) {
        return catalogRepository.insert(new FileEntry(
                null, source.id(), relativePath, relativePath, content.id(), presenceStatus,
                content.sizeBytes(), 100L, 200, 0, 1, 1, null, null));
    }

    private void insertCompletedMetadata(ContentRecord content, MediaMetadataResult result) {
        analysisRepository.insert(new AnalysisRecord(
                null, content.id(), MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                DEFINITION.analyzerId(), DEFINITION.analyzerVersion(),
                DEFINITION.configurationVersion(), DEFINITION.configurationHash(),
                DEFINITION.configurationJson(), resultCodec.write(result),
                "COMPLETED", 1, 1, 1L, 2L, null));
    }

    private void insertCompletedHash(ContentRecord content) {
        AnalysisRecord analysis = analysisRepository.insert(new AnalysisRecord(
                null, content.id(), Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID, Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON, null,
                "COMPLETED", 1, 1, 1L, 2L, null));
        analysisRepository.insert(new ContentHash(analysis.id(), Sha256AnalysisDefinition.ALGORITHM, DIGEST));
    }

    private static MediaMetadataResult imageResult() {
        return new AvailableMediaMetadata(
                1, MediaKind.IMAGE, new ImageMediaMetadata("jpeg", 100, 80), null);
    }
}
