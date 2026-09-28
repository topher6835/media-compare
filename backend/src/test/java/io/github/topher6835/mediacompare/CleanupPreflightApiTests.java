package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.topher6835.mediacompare.analysis.ContentHashFileHasher;
import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.location.*;
import io.github.topher6835.mediacompare.matching.*;
import io.github.topher6835.mediacompare.scan.Version3AuthorityCapture;
import io.github.topher6835.mediacompare.scan.authority.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureMockMvc
@Import({IndexingRunApiTests.Hooks.class, CleanupPreflightApiTests.Hooks.class})
class CleanupPreflightApiTests extends V3ApiTestBase {
    @Autowired CleanupPreflightService service;
    @Autowired TestValidator validator;
    @Autowired SourceMembershipRepository memberships;
    Source source;
    Path keeperPath;
    Path candidatePath;
    long keeper;
    long candidate;
    String digest;

    @BeforeEach
    void group() throws Exception {
        validator.reset();
        source = source("preflight");
        keeperPath = Path.of(source.rootPath()).resolve("a.bin");
        candidatePath = Path.of(source.rootPath()).resolve("b.bin");
        Files.writeString(keeperPath, "copies");
        Files.writeString(candidatePath, "copies");
        completed(source);
        keeper = jdbc.queryForObject("SELECT file_entry_id FROM source_membership WHERE relative_path = 'a.bin'", Long.class);
        candidate = jdbc.queryForObject("SELECT file_entry_id FROM source_membership WHERE relative_path = 'b.bin'", Long.class);
        digest = jdbc.queryForObject("SELECT digest_hex FROM content_hash LIMIT 1", String.class);
    }

    private CleanupPreflightResponse check() {
        return service.preflight(digest, new CleanupPreflightRequest(keeper, List.of(candidate)));
    }
    private void blocked(CleanupPreflightReason reason) {
        var result = check();
        assertEquals(CleanupPreflightStatus.BLOCKED, result.status());
        assertEquals(reason, result.reason());
        assertNull(result.estimatedSavingsBytes());
    }

    @Test
    void readyApiFreshlyHashesKeeperAndCandidateAndChangesNoRowsOrFiles() throws Exception {
        var before = durableState();
        mvc.perform(post("/api/exact-duplicate-groups/" + digest + "/cleanup-preflight")
                .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.sizeBytes").value(6))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.estimatedSavingsBytes").value(6))
                .andExpect(jsonPath("$.keeper.fileEntryId").value(keeper))
                .andExpect(jsonPath("$.candidates[0].fileEntryId").value(candidate));
        assertEquals(2, validator.hashReads.get());
        assertEquals(before, durableState());
        assertEquals("copies", Files.readString(keeperPath));
        assertEquals("copies", Files.readString(candidatePath));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "ABCDEF", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void malformedDigestIs400(String invalid) throws Exception {
        mvc.perform(post("/api/exact-duplicate-groups/" + invalid + "/cleanup-preflight")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isBadRequest());
        assertEquals(0, validator.hashReads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{", "{\"keeperFileEntryId\":0,\"candidateFileEntryIds\":[3]}",
            "{\"keeperFileEntryId\":-2,\"candidateFileEntryIds\":[3]}",
            "{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[]}",
            "{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[0]}",
            "{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[-3]}",
            "{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[null]}",
            "{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[3,3]}",
            "{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[2,3]}"})
    void malformedBodyIs400(String body) throws Exception {
        mvc.perform(post("/api/exact-duplicate-groups/" + digest + "/cleanup-preflight")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertEquals(0, validator.hashReads.get());
    }

    @Test
    void candidateBoundIs400() throws Exception {
        String ids = String.join(",", java.util.stream.LongStream.rangeClosed(1000, 1250)
                .mapToObj(Long::toString).toList());
        mvc.perform(post("/api/exact-duplicate-groups/" + digest + "/cleanup-preflight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keeperFileEntryId\":2,\"candidateFileEntryIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void absentDigestIsBlocked200() throws Exception {
        mvc.perform(post("/api/exact-duplicate-groups/" + "0".repeat(64) + "/cleanup-preflight")
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.reason").value("GROUP_CHANGED"));
    }

    @Test
    void groupNoLongerExists() {
        jdbc.update("UPDATE analysis_record SET status = 'FAILED'");
        blocked(CleanupPreflightReason.GROUP_CHANGED);
    }

    @Test
    void keeperMissing() {
        jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE file_entry_id = ?", keeper);
        blocked(CleanupPreflightReason.KEEPER_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "RETIRED"})
    void missingOrRetiredCandidateIsNeverAnAction(String state) {
        jdbc.update("UPDATE source_membership SET " + (state.equals("MISSING")
                ? "presence_status" : "applicability_status") + " = ? WHERE file_entry_id = ?", state, candidate);
        blocked(CleanupPreflightReason.CANDIDATE_SET_CHANGED);
        assertEquals(0, validator.hashReads.get());
    }

    @Test
    void newPresentPhysicalCopyRequiresReview() throws Exception {
        Files.writeString(Path.of(source.rootPath()).resolve("c.bin"), "copies");
        completed(source);
        blocked(CleanupPreflightReason.CANDIDATE_SET_CHANGED);
    }

    @Test
    void candidateFromAnotherDigestIsNotSubstituted() throws Exception {
        Files.writeString(Path.of(source.rootPath()).resolve("other.bin"), "different");
        completed(source);
        long other = jdbc.queryForObject("SELECT MAX(id) FROM file_entry", Long.class);
        var result = service.preflight(digest, new CleanupPreflightRequest(keeper, List.of(other)));
        assertEquals(CleanupPreflightReason.CANDIDATE_SET_CHANGED, result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"observed_source_location_revision", "observed_location_context_revision", "observed_file_entry_revision"})
    void staleMembershipAuthorityBlocks(String column) {
        jdbc.update("UPDATE source_membership SET " + column + " = " + column + " + 1 WHERE file_entry_id = ?", candidate);
        blocked(CleanupPreflightReason.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void sourceRevisionAdvancedSinceMembershipObservation() {
        advanceSourceRevision();
        blocked(CleanupPreflightReason.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void contextRevisionAdvancedSinceMembershipObservation() {
        advanceContextRevision();
        blocked(CleanupPreflightReason.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void pathAndKeyDisagreementBlocks() {
        jdbc.update("UPDATE file_entry SET location_key = location_key || 'changed' WHERE id = ?", candidate);
        blocked(CleanupPreflightReason.UNSAFE_PATH);
    }

    @Test
    void membershipPathDisagreementBlocks() {
        jdbc.update("UPDATE source_membership SET relative_path = 'wrong.bin', path_key = 'wrong.bin' WHERE file_entry_id = ?", candidate);
        blocked(CleanupPreflightReason.UNSAFE_PATH);
    }

    @Test
    void corruptCompletedArtifactRemainsAnIntegrityFailure() {
        jdbc.update("DELETE FROM content_hash");
        assertThrows(ExactDuplicateIntegrityException.class, this::check);
    }

    @Test
    void freshApfsUnavailable() {
        validator.host.mode = "unavailable";
        blocked(CleanupPreflightReason.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void freshApfsIdentityMismatch() {
        validator.host.mode = "mismatch";
        blocked(CleanupPreflightReason.AUTHORITY_CHANGED);
    }

    @Test
    void changedRootContinuityBlocks() {
        validator.host.mode = "root";
        blocked(CleanupPreflightReason.AUTHORITY_CHANGED);
    }

    @Test
    void changedAuthorityAtEndBlocks() {
        validator.host.failAfter = 1;
        blocked(CleanupPreflightReason.AUTHORITY_CHANGED);
    }

    @Test
    void overlapsCountOnceAndStaleRouteDoesNotPreventTrustedRoute() {
        addOverlap();
        jdbc.update("UPDATE source_membership SET observed_source_location_revision = 0 WHERE source_id = ?", source.id());
        assertEquals(CleanupPreflightStatus.READY, check().status());
        assertEquals(2, validator.hashReads.get());
    }

    @Test
    void missingFile() throws Exception {
        Files.delete(candidatePath); // Test setup only; production preflight has no mutation.
        blocked(CleanupPreflightReason.IO_UNAVAILABLE);
        assertEquals(candidate, check().candidates().getFirst().fileEntryId());
    }

    @Test
    void finalSymlink() throws Exception {
        Files.delete(candidatePath);
        Files.createSymbolicLink(candidatePath, keeperPath);
        blocked(CleanupPreflightReason.UNSAFE_PATH);
    }

    @Test
    void ancestorSymlink() throws Exception {
        Path root = Path.of(source.rootPath());
        Path moved = root.resolveSibling("moved");
        Files.move(root, moved);
        Files.createSymbolicLink(root, moved);
        blocked(CleanupPreflightReason.UNSAFE_PATH);
    }

    @Test
    void sizeChange() throws Exception {
        Files.writeString(candidatePath, "longer bytes");
        blocked(CleanupPreflightReason.FILESYSTEM_CHANGED);
    }

    @Test
    void exactMtimeChange() throws Exception {
        Files.setLastModifiedTime(candidatePath,
                FileTime.from(Files.getLastModifiedTime(candidatePath).toInstant().plusNanos(123456789)));
        blocked(CleanupPreflightReason.FILESYSTEM_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"child", "uuid", "unsupported", "unavailable"})
    void childStorageFailsClosed(String mode) {
        validator.mountMode = mode;
        blocked(mode.equals("unsupported") || mode.equals("unavailable")
                ? CleanupPreflightReason.AUTHORITY_UNAVAILABLE : CleanupPreflightReason.AUTHORITY_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keeper", "candidate"})
    void sameMetadataButDifferentBytesFailFreshHash(String which) throws Exception {
        Path file = which.equals("keeper") ? keeperPath : candidatePath;
        FileTime modified = Files.getLastModifiedTime(file);
        Files.writeString(file, "CHANGED".substring(0, 6));
        Files.setLastModifiedTime(file, modified);
        var result = check();
        assertEquals(CleanupPreflightReason.HASH_MISMATCH, result.reason());
        var failed = which.equals("keeper") ? result.keeper() : result.candidates().getFirst();
        assertEquals(CleanupPreflightReason.HASH_MISMATCH, failed.reason());
        assertEquals(2, validator.hashReads.get());
    }

    @Test
    void changingFileDuringReadFailsClosed() {
        validator.duringRead = () -> {
            try { Files.writeString(keeperPath, "bytes changed during read"); }
            catch (IOException exception) { throw new RuntimeException(exception); }
        };
        blocked(CleanupPreflightReason.FILESYSTEM_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "context", "observation", "membership", "presence", "content", "path"})
    void catalogChangesAfterIoBlockAndIdentifyPhysicalFile(String change) {
        validator.afterValidation = () -> {
            switch (change) {
                case "source" -> advanceSourceRevision();
                case "context" -> advanceContextRevision();
                case "observation" -> jdbc.update("UPDATE file_entry SET observation_revision = observation_revision + 1 WHERE id = ?", keeper);
                case "membership" -> jdbc.update("UPDATE source_membership SET membership_revision = membership_revision + 1 WHERE file_entry_id = ?", keeper);
                case "presence" -> jdbc.update("UPDATE source_membership SET presence_status = 'MISSING' WHERE file_entry_id = ?", keeper);
                case "content" -> jdbc.update("UPDATE file_entry SET current_content_id = NULL WHERE id = ?", keeper);
                case "path" -> jdbc.update("UPDATE file_entry SET location_key = location_key || 'changed' WHERE id = ?", keeper);
                default -> throw new AssertionError();
            }
        };
        var result = check();
        assertEquals(CleanupPreflightStatus.BLOCKED, result.status());
        assertEquals(CleanupPreflightReason.AUTHORITY_CHANGED, result.keeper().reason());
        assertNull(result.estimatedSavingsBytes());
    }

    @Test
    void allOrdinaryFileFailuresAreReported() throws Exception {
        Files.writeString(keeperPath, "larger keeper");
        Files.writeString(candidatePath, "larger candidate");
        var result = check();
        assertEquals(CleanupPreflightReason.FILESYSTEM_CHANGED, result.keeper().reason());
        assertEquals(CleanupPreflightReason.FILESYSTEM_CHANGED, result.candidates().getFirst().reason());
    }

    private void advanceSourceRevision() {
        var root = SourceBindingAuthority.requireCurrentBound(source).macOsApfsSourceRootEvidence();
        var next = new MacOsApfsSourceRootEvidence(1, MacOsApfsSourceRootEvidence.PROFILE, 1,
                root.locationContextId(), root.locationContextRevision(), root.sourceLocationRevision() + 1,
                root.rootLocationPath(), root.rootLocationKey(), root.volumeUuid(), root.rootInode(),
                root.rootBirthTime(), true, false, 2);
        String binding = new SourceBindingEvidenceCodec().encode(new SourceBindingEvidence(1, source.id(), next));
        jdbc.update("UPDATE source SET location_revision = location_revision + 1, binding_evidence_json = ? WHERE id = ?",
                binding, source.id());
    }

    private void advanceContextRevision() {
        var old = new LocationContextAcceptanceEvidenceCodec().decode(jdbc.queryForObject(
                "SELECT continuity_evidence_json FROM location_context WHERE id = ?", String.class, source.boundLocationContextId()));
        String next = new LocationContextAcceptanceEvidenceCodec().encode(new LocationContextAcceptanceEvidence(
                1, old.contextId(), old.contextRevision() + 1, old.macOsApfsEvidence()));
        jdbc.update("UPDATE location_context SET revision = revision + 1, continuity_evidence_json = ? WHERE id = ?",
                next, old.contextId());
    }

    private void addOverlap() {
        Source second = catalog.insert(new Source(null, "Overlap", source.rootPath(), source.rootPathKey(), 0, 1, 1));
        var evidence = SourceBindingAuthority.requireCurrentBound(source).macOsApfsSourceRootEvidence();
        String binding = new SourceBindingEvidenceCodec().encode(new SourceBindingEvidence(1, second.id(), evidence));
        jdbc.update("UPDATE source SET root_path_dialect = 'unix', bound_location_context_id = ?, binding_evidence_json = ?, location_revision = 1 WHERE id = ?",
                source.boundLocationContextId(), binding, second.id());
        jdbc.update("""
                INSERT INTO source_membership (source_id, file_entry_id, relative_path, path_key,
                    applicability_status, presence_status, observed_file_entry_revision,
                    observed_source_location_revision, observed_location_context_revision, first_seen_at_ms, last_seen_at_ms)
                SELECT ?, file_entry_id, relative_path, path_key, 'ACTIVE', 'PRESENT', observed_file_entry_revision,
                    1, 1, 1, 1 FROM source_membership WHERE source_id = ?
                """, second.id(), source.id());
    }

    private String body() {
        return "{\"keeperFileEntryId\":" + keeper + ",\"candidateFileEntryIds\":[" + candidate + "]}";
    }
    private Map<String, List<Map<String, Object>>> durableState() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (String table : List.of("source", "location_context", "source_binding_period", "source_membership",
                "file_entry", "content_record", "analysis_record", "content_hash", "job", "job_stage", "scan_run", "scan_run_source")) {
            state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return state;
    }

    @TestConfiguration
    static class Hooks {
        @Bean @Primary TestValidator preflightValidator(CatalogRepository catalog, LocationContextRepository contexts) {
            return new TestValidator(new Host(catalog, contexts));
        }
    }

    static class Host extends Version3AuthorityCapture {
        String mode = "ok";
        int captures;
        int failAfter = Integer.MAX_VALUE;
        Host(CatalogRepository catalog, LocationContextRepository contexts) { super(catalog, contexts); }
        @Override public ScanAuthorityResult<ScanAuthoritySnapshot> capture(Source source, LocationContext context) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var eligible = ScanObservationAuthority.capture(source, context, null, null);
            if (eligible.reason() != ScanAuthorityReason.AUTHORITY_UNAVAILABLE || context == null) return eligible;
            if (++captures > failAfter || mode.equals("unavailable")) {
                return ScanObservationAuthority.capture(source, context, ContinuityProbeResult.unavailable(), ContinuityProbeResult.unavailable());
            }
            var baseline = LocationContextAcceptanceAuthority.requireCurrentAccepted(context).macOsApfsEvidence();
            var root = SourceBindingAuthority.requireCurrentBound(source).macOsApfsSourceRootEvidence();
            var fresh = new MacOsApfsLocationContextEvidence(1, MacOsApfsLocationContextEvidence.PROFILE, 1,
                    baseline.anchorLocationPath(), baseline.anchorLocationKey(), "apfs",
                    mode.equals("mismatch") ? "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" : baseline.volumeUuid(),
                    baseline.anchorInode(), true, false, 2, baseline.diagnostics());
            var freshRoot = new MacOsApfsSourceRootEvidence(1, MacOsApfsSourceRootEvidence.PROFILE, 1,
                    root.locationContextId(), root.locationContextRevision(), root.sourceLocationRevision(),
                    root.rootLocationPath(), root.rootLocationKey(), root.volumeUuid(),
                    mode.equals("root") ? "999" : root.rootInode(), root.rootBirthTime(), true, false, 2);
            return ScanObservationAuthority.capture(source, context,
                    ContinuityProbeResult.accepted(fresh), ContinuityProbeResult.accepted(freshRoot));
        }
    }

    static class TestValidator extends CleanupPreflightFileValidator {
        final Host host;
        final AtomicInteger hashReads;
        final Reader reader;
        String mountMode = "ok";
        Runnable afterValidation;
        Runnable duringRead;
        TestValidator(Host host) { this(host, new Reader()); }
        private TestValidator(Host host, Reader reader) {
            super(host, reader, path -> reader.owner.mount(path));
            this.host = host;
            this.reader = reader;
            hashReads = reader.reads;
            reader.owner = this;
        }
        void reset() {
            host.mode = "ok"; host.captures = 0; host.failAfter = Integer.MAX_VALUE;
            hashReads.set(0); mountMode = "ok"; afterValidation = null; duringRead = null;
        }
        @Override public CleanupPreflightReason validate(CleanupPreflightCatalog.PhysicalFile file, String digest) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var result = super.validate(file, digest);
            if (afterValidation != null) { var action = afterValidation; afterValidation = null; action.run(); }
            return result;
        }
        ContinuityProbeResult<MacOsApfsMountInspector.MountObservation> mount(Path path) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            boolean file = path.toString().endsWith(".bin");
            if (file && mountMode.equals("unsupported")) return ContinuityProbeResult.unsupported();
            if (file && mountMode.equals("unavailable")) return ContinuityProbeResult.unavailable();
            return ContinuityProbeResult.accepted(new MacOsApfsMountInspector.MountObservation(
                    file && mountMode.equals("child") ? "/dev/disk3s1" : "/dev/disk2s1",
                    file && mountMode.equals("child") ? path.toString() : "/", "apfs",
                    file && mountMode.equals("uuid") ? "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
                            : "11111111-2222-3333-4444-555555555555"));
        }
    }

    static class Reader extends ContentHashFileHasher {
        final AtomicInteger reads = new AtomicInteger();
        TestValidator owner;
        @Override public String hash(ContentHashCandidate candidate) throws IOException {
            reads.incrementAndGet();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return super.hash(candidate);
        }
        @Override protected int read(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
            int count = super.read(channel, buffer);
            if (owner.duringRead != null) { var action = owner.duringRead; owner.duringRead = null; action.run(); }
            return count;
        }
    }
}
