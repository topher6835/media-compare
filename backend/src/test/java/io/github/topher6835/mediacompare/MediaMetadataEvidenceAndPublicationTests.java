package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataAnalysisDefinition;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCache;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataContentCandidate;
import io.github.topher6835.mediacompare.analysis.MediaMetadataFileCandidate;
import io.github.topher6835.mediacompare.analysis.MediaMetadataFileEvidenceValidator;
import io.github.topher6835.mediacompare.analysis.MediaMetadataPublisher;
import io.github.topher6835.mediacompare.analysis.MediaMetadataResult;
import io.github.topher6835.mediacompare.analysis.StaleMediaMetadataEvidenceException;
import io.github.topher6835.mediacompare.analysis.UnsupportedMediaMetadata;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.FileExtensionNormalizer;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class MediaMetadataEvidenceAndPublicationTests {

    private static final MediaMetadataAnalysisDefinition DEFINITION =
            new MediaMetadataAnalysisDefinition("test.metadata", "1", 1, "config-hash", "{}");
    private static final FileTime FIXED_TIME =
            FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_000_000));

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private MediaMetadataCandidateRepository candidateRepository;

    @Autowired
    private MediaMetadataFileEvidenceValidator evidenceValidator;

    @Autowired
    private MediaMetadataPublisher publisher;

    @Autowired
    private MediaMetadataCache metadataCache;

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
        jdbcTemplate.update("DELETE FROM location_context");
    }

    @Test
    void validatesTheSameSafeRegularFileBeforeAndAfterExtraction() throws Exception {
        Fixture fixture = createFixture("valid", List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();

        Path validated = evidenceValidator.validateBeforeExtraction(candidate);
        evidenceValidator.validateAfterExtraction(candidate, validated);

        assertEquals(fixture.paths().getFirst(), validated);
        assertNoMetadataArtifact(fixture.content().id());
    }

    @ParameterizedTest(name = "rejects stale filesystem evidence: {0}")
    @MethodSource("filesystemMutations")
    void rejectsStaleFilesystemEvidenceWithoutPublishing(
            String description, String mutation) throws Exception {
        Fixture fixture = createFixture("filesystem-" + mutation, List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();

        switch (mutation) {
            case "missing" -> Files.delete(fixture.paths().getFirst());
            case "size" -> Files.writeString(fixture.paths().getFirst(), "different-size");
            case "mtime-second" -> Files.setLastModifiedTime(
                    fixture.paths().getFirst(),
                    FileTime.from(Instant.ofEpochSecond(
                            candidate.expectedModifiedTimeEpochSecond() + 1,
                            candidate.expectedModifiedTimeNano())));
            case "mtime-nano" -> candidate = withExpectedModifiedNano(
                    candidate, differentNano(candidate.expectedModifiedTimeNano()));
            case "unsafe-path" -> candidate = withRelativePath(candidate, "../outside.jpg");
            default -> throw new IllegalArgumentException("Unknown mutation " + mutation);
        }

        MediaMetadataFileCandidate staleCandidate = candidate;
        assertThrows(StaleMediaMetadataEvidenceException.class,
                () -> evidenceValidator.validateBeforeExtraction(staleCandidate));
        assertNoMetadataArtifact(fixture.content().id());
    }

    static Stream<Object[]> filesystemMutations() {
        return Stream.of(
                new Object[] { "file disappeared", "missing" },
                new Object[] { "size changed", "size" },
                new Object[] { "modified second changed", "mtime-second" },
                new Object[] { "modified nanosecond changed", "mtime-nano" },
                new Object[] { "portable path became unsafe", "unsafe-path" });
    }

    @Test
    void postValidationRejectsAChangeDuringExtractionWithoutPublishing() throws Exception {
        Fixture fixture = createFixture("post-validation", List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();
        Path validated = evidenceValidator.validateBeforeExtraction(candidate);
        Files.writeString(validated, "different-size");

        assertThrows(StaleMediaMetadataEvidenceException.class,
                () -> evidenceValidator.validateAfterExtraction(candidate, validated));
        assertNoMetadataArtifact(fixture.content().id());
    }

    @Test
    void rejectsSymbolicFileWithoutPublishingWhenLinksAreSupported() throws Exception {
        Fixture fixture = createFixture("symlink", List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();
        Path file = fixture.paths().getFirst();
        Path target = Files.writeString(temporaryDirectory.resolve("symlink-target.jpg"), "same-bytes");
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, target);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(StaleMediaMetadataEvidenceException.class,
                () -> evidenceValidator.validateBeforeExtraction(candidate));
        assertNoMetadataArtifact(fixture.content().id());
    }

    @ParameterizedTest(name = "rejects stale catalog evidence: {0}")
    @MethodSource("catalogMutations")
    void transactionalGuardRejectsStaleCatalogEvidenceWithoutPublishing(
            String description, String mutation) throws Exception {
        Fixture fixture = createFixture("catalog-" + mutation, List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();

        switch (mutation) {
            case "source-revision" -> jdbcTemplate.update(
                    "UPDATE source SET location_revision = location_revision + 1 WHERE id = ?",
                    candidate.sourceId());
            case "missing" -> jdbcTemplate.update(
                    "UPDATE source_membership SET presence_status = 'MISSING' WHERE id = ?",
                    candidate.membershipId());
            case "content" -> {
                ContentRecord replacement = catalogRepository.insert(
                        new ContentRecord(null, candidate.expectedContentSizeBytes(), 1));
                jdbcTemplate.update("UPDATE file_entry SET current_content_id = ? WHERE id = ?",
                        replacement.id(), candidate.fileEntryId());
            }
            case "observation-revision" -> updateFile(
                    candidate, "observation_revision = observation_revision + 1");
            case "size" -> updateFile(candidate, "size_bytes = size_bytes + 1");
            case "mtime-second" -> updateFile(
                    candidate, "modified_time_epoch_second = modified_time_epoch_second + 1");
            case "mtime-nano" -> updateFile(
                    candidate, "modified_time_nano = (modified_time_nano + 1) % 1000000000");
            case "content-size" -> jdbcTemplate.update(
                    "UPDATE content_record SET size_bytes = size_bytes + 1 WHERE id = ?",
                    candidate.contentRecordId());
            default -> throw new IllegalArgumentException("Unknown mutation " + mutation);
        }

        assertThrows(RuntimeException.class,
                () -> publisher.publishIfStillCurrent(
                        candidate, DEFINITION, unsupportedResult(), 10, 20));
        assertNoMetadataArtifact(fixture.content().id());
    }

    @Test
    void failurePublisherRejectsStaleCatalogEvidenceWithoutPublishing() throws Exception {
        Fixture fixture = createFixture("stale-failure-publication", List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();
        jdbcTemplate.update(
                "UPDATE file_entry SET observation_revision = observation_revision + 1 WHERE id = ?",
                candidate.fileEntryId());

        assertThrows(RuntimeException.class,
                () -> publisher.publishFailureIfStillCurrent(
                        candidate, DEFINITION, 10, 20, "Image metadata extraction failed"));
        assertNoMetadataArtifact(fixture.content().id());
    }

    static Stream<Object[]> catalogMutations() {
        return Stream.of(
                new Object[] { "Source location revision changed", "source-revision" },
                new Object[] { "FileEntry became missing", "missing" },
                new Object[] { "current ContentRecord changed", "content" },
                new Object[] { "observation revision changed", "observation-revision" },
                new Object[] { "catalog size changed", "size" },
                new Object[] { "catalog mtime second changed", "mtime-second" },
                new Object[] { "catalog mtime nanosecond changed", "mtime-nano" },
                new Object[] { "ContentRecord size changed", "content-size" });
    }

    @Test
    void publishesTypedCompletedResultAndMakesItReusable() throws Exception {
        Fixture fixture = createFixture("publication", List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();
        Path file = evidenceValidator.validateBeforeExtraction(candidate);
        evidenceValidator.validateAfterExtraction(candidate, file);

        AnalysisRecord stored = publisher.publishIfStillCurrent(
                candidate, DEFINITION, unsupportedResult(), 10, 20);

        assertEquals("COMPLETED", stored.status());
        assertEquals(MediaMetadataAnalysisDefinition.ANALYSIS_TYPE, stored.analysisType());
        assertEquals(unsupportedResult(), metadataCache.findReusableResult(
                fixture.content().id(), DEFINITION).orElseThrow());
        assertTrue(candidateRepository.findCandidates(DEFINITION, 0, 10).isEmpty());
    }

    @Test
    void completedArtifactThatAppearsBeforePublicationWinsWithoutDuplicateRow() throws Exception {
        Fixture fixture = createFixture("publication-race", List.of("candidate.jpg"));
        MediaMetadataFileCandidate candidate = occurrences(fixture).getFirst();
        AnalysisRecord first = publisher.publishIfStillCurrent(
                candidate, DEFINITION, unsupportedResult(), 10, 20);

        AnalysisRecord observed = publisher.publishIfStillCurrent(
                candidate, DEFINITION, unsupportedResult(), 30, 40);

        assertEquals(first.id(), observed.id());
        assertEquals(1, observed.attemptCount());
        assertEquals(1, metadataArtifactCount(fixture.content().id()));
    }

    @Test
    void staleFirstOccurrenceFallsBackToTheSecondOccurrence() throws Exception {
        Fixture fixture = createFixture("stale-fallback", List.of("first.jpg", "second.jpg"));
        List<MediaMetadataFileCandidate> occurrences = occurrences(fixture);
        Files.delete(fixture.paths().getFirst());

        assertTrue(publishFromFirstUsable(occurrences));
        assertEquals(1, metadataArtifactCount(fixture.content().id()));
        assertEquals("COMPLETED", requireMetadataArtifact(fixture.content().id()).status());
    }

    @Test
    void missingFirstOccurrenceFallsBackToTheSecondOccurrence() throws Exception {
        Fixture fixture = createFixture("missing-fallback", List.of("first.jpg", "second.jpg"));
        List<MediaMetadataFileCandidate> beforeMissing = occurrences(fixture);
        jdbcTemplate.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE id = ?",
                beforeMissing.getFirst().membershipId());

        List<MediaMetadataFileCandidate> remaining = occurrences(fixture);
        assertEquals(List.of(beforeMissing.getLast().fileEntryId()),
                remaining.stream().map(MediaMetadataFileCandidate::fileEntryId).toList());
        assertTrue(publishFromFirstUsable(remaining));
        assertEquals("COMPLETED", requireMetadataArtifact(fixture.content().id()).status());
    }

    @Test
    void allUnusableOccurrencesPublishNeitherCompletedNorFailedResult() throws Exception {
        Fixture fixture = createFixture("all-unusable", List.of("first.jpg", "second.jpg"));
        List<MediaMetadataFileCandidate> occurrences = occurrences(fixture);
        Files.delete(fixture.paths().getFirst());
        Files.delete(fixture.paths().getLast());

        assertFalse(publishFromFirstUsable(occurrences));
        assertNoMetadataArtifact(fixture.content().id());
    }

    @Test
    void publisherHasARealTransactionProxyAndFilesystemValidatorDoesNotStartATransaction()
            throws Exception {
        assertTrue(AopUtils.isAopProxy(publisher));
        assertNotNull(MediaMetadataPublisher.class.getMethod(
                "publishIfStillCurrent",
                MediaMetadataFileCandidate.class,
                MediaMetadataAnalysisDefinition.class,
                MediaMetadataResult.class,
                long.class,
                long.class).getAnnotation(Transactional.class));
        assertNotNull(MediaMetadataPublisher.class.getMethod(
                "publishFailureIfStillCurrent",
                MediaMetadataFileCandidate.class,
                MediaMetadataAnalysisDefinition.class,
                long.class,
                long.class,
                String.class).getAnnotation(Transactional.class));
        assertNull(MediaMetadataFileEvidenceValidator.class.getMethod(
                "validateBeforeExtraction", MediaMetadataFileCandidate.class)
                .getAnnotation(Transactional.class));
    }

    private Fixture createFixture(String directoryName, List<String> fileNames) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(directoryName)).toRealPath();
        var paths = new ArrayList<Path>();
        for (String fileName : fileNames) {
            Path file = Files.writeString(root.resolve(fileName), "same-bytes");
            Files.setLastModifiedTime(file, FIXED_TIME);
            paths.add(file);
        }

        Source source = catalogRepository.insert(new Source(
                null, directoryName, root.toString(), root.toString(), 0, 1, 1));
        LocationPath rootLocation = LocationPathParser.parse(LocationDialect.UNIX, root.toString());
        String contextId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String volumeId = "11111111-2222-3333-4444-555555555555";
        var contextEvidence = new MacOsApfsLocationContextEvidence(1,
                MacOsApfsLocationContextEvidence.PROFILE, 1,
                rootLocation, LocationKeyCodec.encode(rootLocation), "apfs", volumeId, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, contextId, 1, contextEvidence));
        jdbcTemplate.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 1, ?, 1, 1)
                """, contextId, new LocationPathCodec().encode(rootLocation),
                LocationKeyCodec.encode(rootLocation).value(), acceptance);
        var rootEvidence = new MacOsApfsSourceRootEvidence(1,
                MacOsApfsSourceRootEvidence.PROFILE, 1, contextId, 1, 1,
                rootLocation, LocationKeyCodec.encode(rootLocation), volumeId, "10",
                new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(
                new SourceBindingEvidence(1, source.id(), rootEvidence));
        jdbcTemplate.update("""
                UPDATE source SET root_path_key = ?, root_path_dialect = 'unix',
                    bound_location_context_id = ?, binding_evidence_json = ?, location_revision = 1
                WHERE id = ?
                """, LocationKeyCodec.encode(rootLocation).value(), contextId, binding, source.id());
        BasicFileAttributes attributes = Files.readAttributes(
                paths.getFirst(), BasicFileAttributes.class);
        ContentRecord content = catalogRepository.insert(
                new ContentRecord(null, attributes.size(), 1));
        for (int index = 0; index < fileNames.size(); index++) {
            BasicFileAttributes fileAttributes = Files.readAttributes(
                    paths.get(index), BasicFileAttributes.class);
            Instant modified = fileAttributes.lastModifiedTime().toInstant();
            LocationPath fileLocation = rootLocation.append(fileNames.get(index));
            FileEntry entry = catalogRepository.insert(new FileEntry(
                    null, "RESOLVED", contextId,
                    new LocationPathCodec().encode(fileLocation),
                    LocationKeyCodec.encode(fileLocation).value(), content.id(),
                    fileAttributes.size(), modified.getEpochSecond(), modified.getNano(),
                    FileExtensionNormalizer.fromRelativePath(fileNames.get(index)), 0, 1, 1));
            jdbcTemplate.update("""
                    INSERT INTO source_membership (source_id, file_entry_id, relative_path,
                        path_key, applicability_status, presence_status,
                        observed_file_entry_revision, first_seen_at_ms, last_seen_at_ms,
                        observed_source_location_revision, observed_location_context_revision)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 'PRESENT', 0, 1, 1, 1, 1)
                    """, source.id(), entry.id(), fileNames.get(index), fileNames.get(index));
        }
        return new Fixture(source, content, List.copyOf(paths));
    }

    private List<MediaMetadataFileCandidate> occurrences(Fixture fixture) {
        MediaMetadataContentCandidate contentCandidate = new MediaMetadataContentCandidate(
                fixture.content().id(), fixture.content().sizeBytes());
        return candidateRepository.findOccurrences(contentCandidate, 0, 100);
    }

    private boolean publishFromFirstUsable(List<MediaMetadataFileCandidate> occurrences) {
        for (MediaMetadataFileCandidate occurrence : occurrences) {
            try {
                Path file = evidenceValidator.validateBeforeExtraction(occurrence);
                evidenceValidator.validateAfterExtraction(occurrence, file);
                publisher.publishIfStillCurrent(
                        occurrence, DEFINITION, unsupportedResult(), 10, 20);
                return true;
            } catch (StaleMediaMetadataEvidenceException exception) {
                // A later occurrence may still provide current evidence for the same ContentRecord.
            }
        }
        return false;
    }

    private void updateFile(MediaMetadataFileCandidate candidate, String assignment) {
        jdbcTemplate.update("UPDATE file_entry SET " + assignment + " WHERE id = ?", candidate.fileEntryId());
    }

    private void assertNoMetadataArtifact(long contentRecordId) {
        assertEquals(0, metadataArtifactCount(contentRecordId));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_record
                WHERE content_record_id = ?
                  AND analysis_type = 'MEDIA_METADATA'
                  AND status = 'FAILED'
                """, Long.class, contentRecordId));
    }

    private long metadataArtifactCount(long contentRecordId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM analysis_record
                WHERE content_record_id = ? AND analysis_type = 'MEDIA_METADATA'
                """, Long.class, contentRecordId);
    }

    private AnalysisRecord requireMetadataArtifact(long contentRecordId) {
        return analysisRepository.findAnalysisRecordByCacheKey(
                contentRecordId,
                MediaMetadataAnalysisDefinition.ANALYSIS_TYPE,
                DEFINITION.analyzerId(),
                DEFINITION.analyzerVersion(),
                DEFINITION.configurationVersion(),
                DEFINITION.configurationHash()).orElseThrow();
    }

    private static MediaMetadataFileCandidate withRelativePath(
            MediaMetadataFileCandidate candidate, String unsafeLocation) {
        return new MediaMetadataFileCandidate(
                candidate.contentRecordId(), candidate.expectedContentSizeBytes(),
                candidate.fileEntryId(), candidate.membershipId(), candidate.sourceId(),
                candidate.sourceLocationRevision(), candidate.contextId(), candidate.contextRevision(),
                candidate.membershipRevision(), unsafeLocation, candidate.locationKey(),
                candidate.observationRevision(), candidate.expectedSizeBytes(),
                candidate.expectedModifiedTimeEpochSecond(),
                candidate.expectedModifiedTimeNano());
    }

    private static MediaMetadataFileCandidate withExpectedModifiedNano(
            MediaMetadataFileCandidate candidate, int expectedNano) {
        return new MediaMetadataFileCandidate(
                candidate.contentRecordId(), candidate.expectedContentSizeBytes(),
                candidate.fileEntryId(), candidate.membershipId(), candidate.sourceId(),
                candidate.sourceLocationRevision(), candidate.contextId(), candidate.contextRevision(),
                candidate.membershipRevision(), candidate.locationPath(), candidate.locationKey(),
                candidate.observationRevision(), candidate.expectedSizeBytes(),
                candidate.expectedModifiedTimeEpochSecond(), expectedNano);
    }

    private static int differentNano(int nano) {
        return nano == 0 ? 500_000_000 : 0;
    }

    private static MediaMetadataResult unsupportedResult() {
        return new UnsupportedMediaMetadata(1);
    }

    private record Fixture(Source source, ContentRecord content, List<Path> paths) {
    }
}
