package io.github.topher6835.mediacompare.scan;

import static io.github.topher6835.mediacompare.filesystem.ExfatSlice3Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import io.github.topher6835.mediacompare.matching.ExactDuplicateService;

class ExfatSlice4FlowTests extends ExfatSlice4TestSupport {
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void overlappingFourStageFlowCountsAndAssignsOnePhysicalObservation(boolean parentFirst) throws Exception {
        long first = source(parentFirst ? ROOT : ROOT.append("Nested"));
        long second = source(parentFirst ? ROOT.append("Nested") : ROOT);
        access.add("C:\\Photos\\Nested\\Photo.jpg", "same retained bytes across several buffers");
        var accepted = admit(first, second);
        assertEquals(2, bundles.authorities(accepted.job().scanRunId()).size());
        assertEquals(1, bundles.authorities(accepted.job().scanRunId()).stream().map(a -> a.bundleUuid()).distinct().count());
        assertEquals("COMPLETED", execution.run(accepted.job().scanRunId()).job().status());
        assertEquals(1, count("file_entry")); assertEquals(2, count("source_membership"));
        assertEquals(1, count("content_record")); assertEquals(1, count("content_hash"));
        assertEquals(1, access.opens); assertEquals(1, access.closes);
        assertEquals(List.of(1L), access.rowsAtFileClose);
        assertEquals(1, access.enumerations.get(ROOT)); assertEquals(1, access.enumerations.get(ROOT.append("Nested")));
        var file = onlyFile(); var receipt = OccurrenceProfileValidation.requireExfat(file);
        assertEquals(2, receipt.sources().size()); assertEquals(0, file.observationRevision());
        assertEquals(receipt.sha256(), jdbc.queryForObject("SELECT digest_hex FROM content_hash", String.class));
        assertEquals(1, app.getBean(JobRepository.class).findJobStageByJobIdAndType(accepted.job().id(), "DISCOVERY").orElseThrow().progressCompleted());
        assertEquals(1, app.getBean(JobRepository.class).findJobStageByJobIdAndType(accepted.job().id(), "CONTENT_ASSIGNMENT").orElseThrow().progressCompleted());
        assertEquals(1, app.getBean(JobRepository.class).findJobStageByJobIdAndType(accepted.job().id(), "CONTENT_HASHING").orElseThrow().progressCompleted());
        assertFalse(WindowsExfatSupport.PRODUCTION.available());
        assertEquals(4, java.util.UUID.fromString(file.occurrenceToken()).version());
        assertEquals(2, java.util.UUID.fromString(file.occurrenceToken()).variant());
    }

    @Test void independentUnchangedRescanAlwaysReadsFullyAndCreatesDistinctOccurrenceAndContent() throws Exception {
        long id = source(ROOT); access.add("C:\\Photos\\photo.jpg", "identical bytes and metadata");
        var first = admit(id); execution.run(first.job().scanRunId()); var prior = onlyFile();
        prepare(id); var second = admit(id); execution.run(second.job().scanRunId()); var next = onlyFile();
        assertNotEquals(prior.id(), next.id()); assertNotEquals(prior.occurrenceToken(), next.occurrenceToken());
        assertNotEquals(prior.currentContentId(), next.currentContentId());
        assertEquals(2, count("file_entry")); assertEquals(2, count("content_record")); assertEquals(2, count("content_hash"));
        assertEquals(1, active()); assertEquals(2 * "identical bytes and metadata".length(), access.bytes);
        assertEquals(2, access.opens); assertEquals(4, access.evidenceReads);
        var receipt = OccurrenceProfileValidation.requireExfat(next);
        var duplicates = app.getBean(ExactDuplicateService.class).findGroup(receipt.sha256());
        assertEquals(1, duplicates.orElseThrow().summary().presentOccurrenceCount(),
                "Retired equal-byte history cannot inflate current duplicate counts");
        assertEquals(prior, file(prior.id()), "Supersession does not rewrite historical evidence/content");
    }

    @Test void subsetPositiveRetiresUnselectedOverlappingRouteWithoutReplacement() throws Exception {
        long parent = source(ROOT), nested = source(ROOT.append("Nested"));
        access.add("C:\\Photos\\Nested\\photo.jpg", "bytes");
        var first = admit(parent, nested); execution.run(first.job().scanRunId());
        jdbc.update("UPDATE source_membership SET presence_status='MISSING' WHERE source_id=?", parent);
        prepare(nested); var next = admit(nested); execution.run(next.job().scanRunId());
        assertEquals(3, count("source_membership")); assertEquals(1, active());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_membership WHERE source_id=? AND applicability_status='ACTIVE'", Long.class, parent));
        assertEquals("MISSING", jdbc.queryForObject("SELECT presence_status FROM source_membership WHERE source_id=?", String.class, parent));
        assertEquals(1, jdbc.queryForObject("SELECT membership_revision FROM source_membership WHERE source_id=?", Long.class, parent));
        assertEquals(1, OccurrenceProfileValidation.requireExfat(onlyFile()).sources().size());
    }

    @Test void disjointRootsTraverseIndependentlyAndEqualBytesDoNotMergeContent() throws Exception {
        long first = source(ROOT), second = source(path("C:\\Other"));
        access.add("C:\\Photos\\one.jpg", "same"); access.add("C:\\Other\\two.jpg", "same");
        var accepted = admit(first, second); execution.run(accepted.job().scanRunId());
        assertEquals(2, access.opens); assertEquals(2, count("content_record"));
        assertEquals(1, access.enumerations.get(ROOT)); assertEquals(1, access.enumerations.get(path("C:\\Other")));
        var group = app.getBean(ExactDuplicateService.class).findGroup(OccurrenceProfileValidation.requireExfat(onlyFile()).sha256()).orElseThrow();
        assertEquals(2, group.summary().presentOccurrenceCount()); assertEquals(2, group.summary().sourceCount());
    }

    @ParameterizedTest @ValueSource(strings = {"C:\\Photos\\photo.jpg", "C:\\Photos\\PHOTO.jpg", "C:\\Photos\\NESTED\\Photo.jpg"})
    void exactAndCaseOnlyFileOrAncestorSpellingSupersedesWithoutHistoricalReopen(String nextRoute) throws Exception {
        long parent = source(ROOT);
        String original = nextRoute.contains("NESTED") ? "C:\\Photos\\Nested\\Photo.jpg" : "C:\\Photos\\photo.jpg";
        access.add(original, "same"); var first = admit(parent); execution.run(first.job().scanRunId()); var old = onlyFile();
        access.files.clear(); access.add(nextRoute, "same"); prepare(parent);
        var second = admit(parent); execution.run(second.job().scanRunId()); var current = onlyFile();
        assertEquals(key(path(nextRoute)), current.locationKey()); assertEquals(lp(path(nextRoute)), current.locationPath());
        assertEquals(1, active()); assertEquals(2, access.opens); assertNotEquals(old.occurrenceToken(), current.occurrenceToken());
        assertEquals(old.locationKey(), file(old.id()).locationKey());
    }

    @Test void completeTraversalMarksOnlySelectedSourceUnseenMembershipMissing() throws Exception {
        long parent = source(ROOT), nested = source(ROOT.append("Nested"));
        access.add("C:\\Photos\\Nested\\photo.jpg", "bytes"); var first = admit(parent, nested); execution.run(first.job().scanRunId());
        access.files.clear(); prepare(parent); var next = admit(parent); execution.run(next.job().scanRunId());
        assertEquals("MISSING", jdbc.queryForObject("SELECT presence_status FROM source_membership WHERE source_id=?", String.class, parent));
        assertEquals("PRESENT", jdbc.queryForObject("SELECT presence_status FROM source_membership WHERE source_id=?", String.class, nested));
    }

    @Test void approvedUnresolvedHistoryCollisionRetiresAndPreservesHistory() throws Exception {
        long id = source(ROOT);
        var catalog = app.getBean(CatalogRepository.class);
        var legacy = catalog.insert(new FileEntry(null, "UNRESOLVED", null, null, null, null, 5, 500L, 0, "jpg", 0, 1, 1));
        app.getBean(SourceMembershipRepository.class).insert(new SourceMembership(null, id, legacy.id(), "photo.jpg", "photo.jpg",
                "ACTIVE", "PRESENT", 0, 0, 1, 1, null, null, null, null));
        access.add("C:\\Photos\\photo.jpg", "bytes"); var accepted = admit(id); execution.run(accepted.job().scanRunId());
        assertEquals(2, count("file_entry")); assertEquals(1, active()); assertEquals(legacy, file(legacy.id()));
    }
}
