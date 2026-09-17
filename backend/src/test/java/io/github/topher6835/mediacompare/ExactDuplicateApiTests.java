package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.AnalysisRepository;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.analysis.Sha256AnalysisDefinition;
import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.matching.ExactDuplicateIntegrityException;
import io.github.topher6835.mediacompare.matching.ExactDuplicateFilter;
import io.github.topher6835.mediacompare.matching.ExactDuplicateService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ExactDuplicateApiTests {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private ExactDuplicateService exactDuplicateService;

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
    void listsAndDetailsDerivedGroupWithDistinctMembersAndRetainedOccurrences() throws Exception {
        String digest = digest(10);
        Source firstSource = insertSource("First", temporaryDirectory.resolve("unavailable-first"));
        Source secondSource = insertSource("Second", temporaryDirectory.resolve("unavailable-second"));
        ContentRecord first = insertHashedContent(100, digest);
        ContentRecord second = insertHashedContent(100, digest);
        ContentRecord thirdWithoutOccurrence = insertHashedContent(100, digest);
        assertNotEquals(first.id(), second.id());

        FileEntry firstMissing = insertOccurrence(firstSource, first, "a/missing.dat", "MISSING");
        FileEntry firstPresent = insertOccurrence(secondSource, first, "z/present.dat", "PRESENT");
        FileEntry secondPresent = insertOccurrence(firstSource, second, "b/present.dat", "PRESENT");
        DurableState before = durableState();

        mockMvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].digestHex").value(digest))
                .andExpect(jsonPath("$.groups[0].sizeBytes").value(100))
                .andExpect(jsonPath("$.groups[0].contentRecordCount").value(3))
                .andExpect(jsonPath("$.groups[0].redundantContentRecordCount").value(2))
                .andExpect(jsonPath("$.groups[0].presentOccurrenceCount").value(2))
                .andExpect(jsonPath("$.groups[0].missingOccurrenceCount").value(1))
                .andExpect(jsonPath("$.groups[0].sourceCount").value(2))
                .andExpect(jsonPath("$.groups[0].potentialStorageSavingsBytes").value(100))
                .andExpect(jsonPath("$.nextAfterDigestHex").doesNotExist());

        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", digest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentRecordCount").value(3))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].contentRecordId").value(first.id()))
                .andExpect(jsonPath("$.members[1].contentRecordId").value(second.id()))
                .andExpect(jsonPath("$.members[2].contentRecordId").value(thirdWithoutOccurrence.id()))
                .andExpect(jsonPath("$.occurrences.length()").value(3))
                .andExpect(jsonPath("$.occurrences[0].fileEntryId").value(firstMissing.id()))
                .andExpect(jsonPath("$.occurrences[0].presenceStatus").value("MISSING"))
                .andExpect(jsonPath("$.occurrences[1].fileEntryId").value(firstPresent.id()))
                .andExpect(jsonPath("$.occurrences[1].sourceName").value("Second"))
                .andExpect(jsonPath("$.occurrences[2].fileEntryId").value(secondPresent.id()))
                .andExpect(jsonPath("$.occurrences[2].relativePath").value("b/present.dat"));

        mockMvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk());
        assertEquals(before, durableState());
    }

    @Test
    void singletonAndUnknownDigestsAreNotGroups() throws Exception {
        String singleton = digest(20);
        String otherSingleton = digest(21);
        insertHashedContent(10, singleton);
        insertHashedContent(10, otherSingleton);

        mockMvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0));
        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", singleton))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", digest(999)))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersOtherProvenanceAndNonCompletedArtifacts() throws Exception {
        String digest = digest(30);
        insertHashedContent(10, digest);
        insertHashedContent(10, digest);

        insertArtifact(insertContent(10), "COMPLETED", "OTHER_HASH", "other", "1",
                1, "other", "{}", "SHA-256", digest, true);
        insertArtifact(insertContent(10), "COMPLETED", Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID, "2", 1,
                Sha256AnalysisDefinition.CONFIGURATION_HASH, "{}", "SHA-256", digest, true);
        insertArtifact(insertContent(10), "COMPLETED", Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID, Sha256AnalysisDefinition.ANALYZER_VERSION, 2,
                "different-configuration", "{\"different\":true}", "SHA-256", digest, true);
        insertArtifact(insertContent(10), "RUNNING", Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID, Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON, null, null, false);

        mockMvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].contentRecordCount").value(2));
    }

    @ParameterizedTest(name = "completed exact artifact integrity failure: {0}")
    @MethodSource("invalidArtifacts")
    void rejectsMalformedCompletedExactArtifacts(
            String description, String mutation) {
        ContentRecord content = insertContent(10);
        String configurationJson = "configuration".equals(mutation)
                ? "{\"unexpected\":true}"
                : Sha256AnalysisDefinition.CONFIGURATION_JSON;
        String algorithm = "algorithm".equals(mutation)
                ? "SHA-1"
                : Sha256AnalysisDefinition.ALGORITHM;
        String artifactDigest = switch (mutation) {
            case "short-digest" -> "abc123";
            case "non-hex-digest" -> "g".repeat(64);
            case "uppercase-digest" -> "A".repeat(64);
            default -> digest(40);
        };
        insertArtifact(content, "COMPLETED",
                Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH,
                configurationJson,
                algorithm,
                artifactDigest,
                !"missing".equals(mutation));

        assertThrows(ExactDuplicateIntegrityException.class,
                () -> exactDuplicateService.findGroups(null, null));
        assertThrows(ExactDuplicateIntegrityException.class,
                () -> exactDuplicateService.findGroups(
                        null, null, ExactDuplicateFilter.from(null, List.of("jpg"))));
        assertThrows(ExactDuplicateIntegrityException.class,
                () -> exactDuplicateService.findFilterOptions());
    }

    static Stream<Object[]> invalidArtifacts() {
        return Stream.of(
                new Object[] { "missing ContentHash", "missing" },
                new Object[] { "wrong algorithm", "algorithm" },
                new Object[] { "malformed lowercase digest length", "short-digest" },
                new Object[] { "malformed lowercase non-hex digest", "non-hex-digest" },
                new Object[] { "uppercase digest", "uppercase-digest" },
                new Object[] { "inconsistent configuration JSON", "configuration" });
    }

    @Test
    void rejectsSizeDisagreementWithinTrustedDigestGroup() {
        String digest = digest(50);
        insertHashedContent(10, digest);
        insertHashedContent(11, digest);

        assertThrows(ExactDuplicateIntegrityException.class,
                () -> exactDuplicateService.findGroups(null, null));
        assertThrows(ExactDuplicateIntegrityException.class,
                () -> exactDuplicateService.findGroup(digest));
    }

    @Test
    void keysetPaginatesGroupsAndRejectsInvalidInput() throws Exception {
        String firstDigest = digest(100);
        String secondDigest = digest(200);
        String thirdDigest = digest(300);
        insertGroup(firstDigest, 10, 2);
        insertGroup(secondDigest, 20, 2);
        insertGroup(thirdDigest, 30, 2);

        mockMvc.perform(get("/api/exact-duplicate-groups").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.groups[0].digestHex").value(firstDigest))
                .andExpect(jsonPath("$.groups[1].digestHex").value(secondDigest))
                .andExpect(jsonPath("$.nextAfterDigestHex").value(secondDigest));

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("afterDigestHex", secondDigest)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].digestHex").value(thirdDigest))
                .andExpect(jsonPath("$.nextAfterDigestHex").doesNotExist());

        mockMvc.perform(get("/api/exact-duplicate-groups").param("afterDigestHex", "abc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exact-duplicate-groups").param("afterDigestHex", "A".repeat(64)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exact-duplicate-groups").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exact-duplicate-groups").param("limit", "251"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", "invalid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersThroughRetainedOccurrencesWhileKeepingWholeGroupValues() throws Exception {
        String digest = digest(60);
        Source firstSource = insertSource("First", temporaryDirectory.resolve("unavailable-first"));
        Source secondSource = insertSource("Second", temporaryDirectory.resolve("unavailable-second"));
        ContentRecord first = insertHashedContent(100, digest);
        ContentRecord second = insertHashedContent(100, digest);
        insertHashedContent(100, digest);
        insertOccurrence(firstSource, first, "photos/first.JPG", "PRESENT");
        insertOccurrence(secondSource, first, "photos/second.jpeg", "MISSING");
        insertOccurrence(firstSource, second, "videos/clip.MP4", "PRESENT");
        insertOccurrence(secondSource, second, "other/retained.xyz", "MISSING");
        DurableState before = durableState();

        mockMvc.perform(get("/api/exact-duplicate-groups").param("extension", "jPg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].contentRecordCount").value(3))
                .andExpect(jsonPath("$.groups[0].redundantContentRecordCount").value(2))
                .andExpect(jsonPath("$.groups[0].presentOccurrenceCount").value(2))
                .andExpect(jsonPath("$.groups[0].missingOccurrenceCount").value(2))
                .andExpect(jsonPath("$.groups[0].sourceCount").value(2))
                .andExpect(jsonPath("$.groups[0].potentialStorageSavingsBytes").value(100))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingOccurrenceCount").value(1))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingExtensions[0]").value("JPG"));

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("extension", "JPG", "XYZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingOccurrenceCount").value(2))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingExtensions[0]").value("JPG"))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingExtensions[1]").value("XYZ"));

        mockMvc.perform(get("/api/exact-duplicate-groups").param("extension", "XYZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingOccurrenceCount").value(1))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingExtensions[0]").value("XYZ"));

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("fileCategory", "photo", "VIDEO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingOccurrenceCount").value(3))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingExtensions.length()").value(3));

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("fileCategory", "PHOTO")
                        .param("extension", "JPG", "HEIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingOccurrenceCount").value(1))
                .andExpect(jsonPath("$.groups[0].filterMatch.matchingExtensions[0]").value("JPG"));

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("fileCategory", "PHOTO")
                        .param("extension", "PDF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0));
        mockMvc.perform(get("/api/exact-duplicate-groups").param("extension", "unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(0));
        mockMvc.perform(get("/api/exact-duplicate-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].filterMatch").value(nullValue()));

        assertEquals(before, durableState());
    }

    @Test
    void appliesOccurrenceFiltersBeforeDigestPagination() throws Exception {
        Source source = insertSource("Catalog", temporaryDirectory.resolve("unavailable"));
        String firstDigest = digest(100);
        String excludedDigest = digest(200);
        String lastDigest = digest(300);
        insertGroupWithOccurrence(source, firstDigest, "first.jpg");
        insertGroupWithOccurrence(source, excludedDigest, "middle.pdf");
        insertGroupWithOccurrence(source, lastDigest, "last.JPG");

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("extension", "JPG")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].digestHex").value(firstDigest))
                .andExpect(jsonPath("$.nextAfterDigestHex").value(firstDigest));

        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("extension", "JPG")
                        .param("afterDigestHex", firstDigest)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].digestHex").value(lastDigest))
                .andExpect(jsonPath("$.nextAfterDigestHex").doesNotExist());
    }

    @Test
    void filteredDetailReturnsTheWholeGroupWithOccurrenceMatchContext() throws Exception {
        String digest = digest(70);
        Source firstSource = insertSource("First", temporaryDirectory.resolve("unavailable-first"));
        Source secondSource = insertSource("Second", temporaryDirectory.resolve("unavailable-second"));
        ContentRecord first = insertHashedContent(100, digest);
        ContentRecord second = insertHashedContent(100, digest);
        insertHashedContent(100, digest);
        insertOccurrence(firstSource, first, "photo.JPG", "PRESENT");
        insertOccurrence(secondSource, first, "clip.mov", "MISSING");
        insertOccurrence(firstSource, second, "unclassified.xyz", "PRESENT");
        insertOccurrence(secondSource, second, "README", "MISSING");

        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", digest)
                        .param("fileCategory", "PHOTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.occurrences.length()").value(4))
                .andExpect(jsonPath("$.occurrences[0].extension").value("JPG"))
                .andExpect(jsonPath("$.occurrences[0].fileCategory").value("PHOTO"))
                .andExpect(jsonPath("$.occurrences[0].matchesFilter").value(true))
                .andExpect(jsonPath("$.occurrences[1].extension").value("MOV"))
                .andExpect(jsonPath("$.occurrences[1].fileCategory").value("VIDEO"))
                .andExpect(jsonPath("$.occurrences[1].matchesFilter").value(false))
                .andExpect(jsonPath("$.occurrences[2].extension").value("XYZ"))
                .andExpect(jsonPath("$.occurrences[2].fileCategory").value(nullValue()))
                .andExpect(jsonPath("$.occurrences[2].matchesFilter").value(false))
                .andExpect(jsonPath("$.occurrences[3].extension").value(nullValue()))
                .andExpect(jsonPath("$.occurrences[3].fileCategory").value(nullValue()))
                .andExpect(jsonPath("$.occurrences[3].matchesFilter").value(false));

        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", digest)
                        .param("extension", "PDF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.occurrences.length()").value(4))
                .andExpect(jsonPath("$.occurrences[0].matchesFilter").value(false))
                .andExpect(jsonPath("$.occurrences[3].matchesFilter").value(false));

        mockMvc.perform(get("/api/exact-duplicate-groups/{digest}", digest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrences[0].matchesFilter").value(true))
                .andExpect(jsonPath("$.occurrences[1].matchesFilter").value(true))
                .andExpect(jsonPath("$.occurrences[2].matchesFilter").value(true))
                .andExpect(jsonPath("$.occurrences[3].matchesFilter").value(true));
    }

    @Test
    void reportsCatalogWideFilterOptionsAndRejectsInvalidFilters() throws Exception {
        Source source = insertSource("Catalog", temporaryDirectory.resolve("unavailable"));
        String mixedDigest = digest(80);
        ContentRecord mixedFirst = insertHashedContent(10, mixedDigest);
        ContentRecord mixedSecond = insertHashedContent(10, mixedDigest);
        insertOccurrence(source, mixedFirst, "first.JPG", "PRESENT");
        insertOccurrence(source, mixedFirst, "second.jpg", "MISSING");
        insertOccurrence(source, mixedSecond, "unknown.XYZ", "PRESENT");
        insertOccurrence(source, mixedSecond, "README", "PRESENT");

        String secondDigest = digest(81);
        ContentRecord secondFirst = insertHashedContent(10, secondDigest);
        insertHashedContent(10, secondDigest);
        insertOccurrence(source, secondFirst, "third.Jpg", "PRESENT");

        ContentRecord singleton = insertHashedContent(10, digest(82));
        insertOccurrence(source, singleton, "ignored.gif", "PRESENT");
        DurableState before = durableState();

        mockMvc.perform(get("/api/exact-duplicate-groups/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions.length()").value(2))
                .andExpect(jsonPath("$.extensions[0].extension").value("JPG"))
                .andExpect(jsonPath("$.extensions[0].fileCategory").value("PHOTO"))
                .andExpect(jsonPath("$.extensions[0].exactDuplicateGroupCount").value(2))
                .andExpect(jsonPath("$.extensions[0].retainedOccurrenceCount").value(3))
                .andExpect(jsonPath("$.extensions[1].extension").value("XYZ"))
                .andExpect(jsonPath("$.extensions[1].fileCategory").value(nullValue()))
                .andExpect(jsonPath("$.extensions[1].exactDuplicateGroupCount").value(1))
                .andExpect(jsonPath("$.extensions[1].retainedOccurrenceCount").value(1));

        mockMvc.perform(get("/api/exact-duplicate-groups").param("fileCategory", "audio"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exact-duplicate-groups").param("extension", ".jpg"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/exact-duplicate-groups").param("extension", " "))
                .andExpect(status().isBadRequest());
        String[] excessiveExtensions = new String[101];
        for (int index = 0; index < excessiveExtensions.length; index++) {
            excessiveExtensions[index] = "x" + index;
        }
        mockMvc.perform(get("/api/exact-duplicate-groups")
                        .param("extension", excessiveExtensions))
                .andExpect(status().isBadRequest());

        assertEquals(before, durableState());
    }

    private void insertGroup(String digest, long sizeBytes, int memberCount) {
        for (int index = 0; index < memberCount; index++) {
            insertHashedContent(sizeBytes, digest);
        }
    }

    private void insertGroupWithOccurrence(Source source, String digest, String relativePath) {
        ContentRecord first = insertHashedContent(10, digest);
        insertHashedContent(10, digest);
        insertOccurrence(source, first, relativePath, "PRESENT");
    }

    private ContentRecord insertHashedContent(long sizeBytes, String digest) {
        ContentRecord content = insertContent(sizeBytes);
        insertArtifact(content, "COMPLETED",
                Sha256AnalysisDefinition.ANALYSIS_TYPE,
                Sha256AnalysisDefinition.ANALYZER_ID,
                Sha256AnalysisDefinition.ANALYZER_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_VERSION,
                Sha256AnalysisDefinition.CONFIGURATION_HASH,
                Sha256AnalysisDefinition.CONFIGURATION_JSON,
                Sha256AnalysisDefinition.ALGORITHM,
                digest,
                true);
        return content;
    }

    private AnalysisRecord insertArtifact(
            ContentRecord content,
            String status,
            String analysisType,
            String analyzerId,
            String analyzerVersion,
            long configurationVersion,
            String configurationHash,
            String configurationJson,
            String algorithm,
            String digest,
            boolean insertHash) {
        boolean completed = "COMPLETED".equals(status);
        AnalysisRecord analysis = analysisRepository.insert(new AnalysisRecord(
                null,
                content.id(),
                analysisType,
                analyzerId,
                analyzerVersion,
                configurationVersion,
                configurationHash,
                configurationJson,
                null,
                status,
                1,
                1,
                1L,
                completed ? 2L : null,
                null));
        if (insertHash) {
            analysisRepository.insert(new ContentHash(analysis.id(), algorithm, digest));
        }
        return analysis;
    }

    private ContentRecord insertContent(long sizeBytes) {
        return catalogRepository.insert(new ContentRecord(null, sizeBytes, 1));
    }

    private Source insertSource(String name, Path unavailableRoot) {
        String rootPath = unavailableRoot.toAbsolutePath().toString();
        return catalogRepository.insert(new Source(null, name, rootPath, rootPath, 0, 1, 1));
    }

    private FileEntry insertOccurrence(
            Source source, ContentRecord content, String relativePath, String presenceStatus) {
        return catalogRepository.insert(new FileEntry(
                null,
                source.id(),
                relativePath,
                relativePath,
                content.id(),
                presenceStatus,
                content.sizeBytes(),
                100L,
                200,
                0,
                1,
                1,
                null,
                null));
    }

    private DurableState durableState() {
        return new DurableState(
                table("source"),
                table("file_entry"),
                table("content_record"),
                table("working_set"),
                table("working_set_content"),
                table("scan_run"),
                table("scan_run_source"),
                table("job"),
                table("job_stage"),
                table("analysis_record"),
                table("content_hash"));
    }

    private List<Map<String, Object>> table(String tableName) {
        return jdbcTemplate.queryForList("SELECT * FROM " + tableName + " ORDER BY rowid");
    }

    private static String digest(long value) {
        return "%064x".formatted(value);
    }

    private record DurableState(
            List<Map<String, Object>> sources,
            List<Map<String, Object>> files,
            List<Map<String, Object>> contents,
            List<Map<String, Object>> workingSets,
            List<Map<String, Object>> workingSetContents,
            List<Map<String, Object>> scanRuns,
            List<Map<String, Object>> scanRunSources,
            List<Map<String, Object>> jobs,
            List<Map<String, Object>> stages,
            List<Map<String, Object>> analyses,
            List<Map<String, Object>> hashes) {
    }
}
