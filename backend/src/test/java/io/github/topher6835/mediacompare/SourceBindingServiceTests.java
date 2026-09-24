package io.github.topher6835.mediacompare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.LocationContext;
import io.github.topher6835.mediacompare.catalog.LocationContext.ContinuityStatus;
import io.github.topher6835.mediacompare.catalog.LocationContext.LifecycleStatus;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextReplacementService;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextRetirementService;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.catalog.SourceBindingCapture;
import io.github.topher6835.mediacompare.catalog.SourceBindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourceBindingService;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPath;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidenceCodec;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;

@SpringBootTest
@Import(SourceBindingServiceTests.ReservationHooks.class)
@DirtiesContext
class SourceBindingServiceTests {
    @TempDir static Path databaseDirectory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + databaseDirectory.resolve("binding.db") + "?foreign_keys=on");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogRepository sources;
    @Autowired private LocationContextRepository contexts;
    @Autowired private SourceBindingService binding;
    @Autowired private LocationContextRetirementService retirement;
    @Autowired private LocationContextReplacementService replacement;
    @Autowired private ReservationCoordinator coordinator;

    private final LocationPathCodec pathCodec = new LocationPathCodec();
    private final LocationContextAcceptanceEvidenceCodec acceptanceCodec =
            new LocationContextAcceptanceEvidenceCodec();
    private final SourceBindingEvidenceCodec bindingCodec = new SourceBindingEvidenceCodec();

    @BeforeEach
    @AfterEach
    void clear() {
        coordinator.gate = null;
        coordinator.forceGuardMiss = false;
        jdbc.update("DELETE FROM file_entry");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM location_context");
    }

    @Test
    void bindsSourceAtEqualAnchorAndPreservesOtherFields() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive");
        FileEntry file = sources.insert(new FileEntry(null, source.id(), "photo.jpg", "photo.jpg",
                null, "PRESENT", 12, null, null, 0, 10, 12, null, null));
        Source bound = bind(source, context, capture(source, context, anchor()));

        assertEquals(new Source(source.id(), source.name(), source.rootPath(), key(anchor()),
                5, "unix", context.id(), bound.bindingEvidenceJson(), source.createdAtMs(), 25), bound);
        assertEquals(bound, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
        assertEquals(file, sources.findFileEntryById(file.id()).orElseThrow());
        SourceBindingEvidence envelope = SourceBindingAuthority.requireCurrentBound(bound);
        assertEquals(source.id(), envelope.sourceId());
        assertEquals(5, envelope.macOsApfsSourceRootEvidence().sourceLocationRevision());
        assertEquals(context.id(), envelope.macOsApfsSourceRootEvidence().locationContextId());
        assertEquals(context.revision(), envelope.macOsApfsSourceRootEvidence().locationContextRevision());
        assertEquals(rootEvidence(context.id(), 8, 5, anchor(), VOLUME_UUID, true, false),
                envelope.macOsApfsSourceRootEvidence());
        assertEquals(envelope, bindingCodec.decode(bound.bindingEvidenceJson()));
    }

    @Test
    void bindsStructuralDescendantUsingPrecapturedTypedResults() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        assertEquals(key(photos()), bound.rootPathKey());
        assertEquals(source.rootPath(), bound.rootPath());
    }

    @Test
    void repeatedBindingConflictsWithoutChangingRows() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        assertFailureUnchanged(SourceBindingConflictException.class, bound, context, 5, 8, 30,
                capture(bound, context, photos(), 6));
    }

    @Test
    void nonlegacySourceShapesConflict() {
        LocationContext context = acceptedContext(anchor());
        Source dialect = sources.insert(new Source(null, "Dialect", "/Volumes/Archive/Photos",
                "/Volumes/Archive/Photos", 4, "unix", null, null, 10, 12));
        assertFailureUnchanged(SourceBindingConflictException.class, dialect, context, 4, 8, 25,
                capture(dialect, context, photos()));
        Source evidence = sources.insert(new Source(null, "Evidence", "/Volumes/Archive/Other",
                "/Volumes/Archive/Other", 4, null, null, "{}", 10, 12));
        assertFailureUnchanged(SourceBindingConflictException.class, evidence, context, 4, 8, 25,
                capture(evidence, context, otherInside()));
        Source keyMismatch = sources.insert(new Source(null, "Key", "/Volumes/Archive/Third",
                "legacy-other", 4, null, null, null, 10, 12));
        assertFailureUnchanged(SourceBindingConflictException.class, keyMismatch, context, 4, 8, 25,
                capture(keyMismatch, context, thirdInside()));
    }

    @Test
    void invalidConfiguredRootAndExactSpellingMismatchFail() {
        LocationContext context = acceptedContext(anchor());
        Source invalid = legacySource("/Volumes/Archive/Photos/");
        assertFailureUnchanged(IllegalArgumentException.class, invalid, context, 4, 8, 25,
                capture(invalid, context, photos()));

        Source alternate = legacySource("/Volumes/archive/Photos");
        assertFailureUnchanged(IllegalArgumentException.class, alternate, context, 4, 8, 25,
                capture(alternate, context, photos()));
    }

    @Test
    void staleCaptureRevisionsAndTimestampFail() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25,
                new SourceBindingCapture(source.id(), "/Different",
                        ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                        ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                                VOLUME_UUID, true, false))));
        assertFailureUnchanged(SourceBindingConflictException.class, source, context, 3, 8, 25,
                capture(source, context, photos()));
        assertFailureUnchanged(SourceBindingConflictException.class, source, context, 4, 7, 25,
                capture(source, context, photos()));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 11,
                capture(source, context, photos()));
    }

    @Test
    void revisionOverflowAndMissingRowsFail() {
        LocationContext context = acceptedContext(anchor());
        Source overflow = sources.insert(new Source(null, "Overflow", "/Volumes/Archive/Photos",
                "/Volumes/Archive/Photos", Long.MAX_VALUE, 10, 12));
        assertFailureUnchanged(IllegalStateException.class, overflow, context, Long.MAX_VALUE, 8, 25,
                capture(overflow, context, photos(), 5));
        assertThrows(NoSuchElementException.class, () -> binding.bindUnboundSource(999999, 4,
                context.id(), 8, 25, capture(overflow, context, photos())));
        Source source = legacySource("/Volumes/Archive/Other");
        assertThrows(NoSuchElementException.class, () -> binding.bindUnboundSource(source.id(), 4,
                UUID.randomUUID().toString(), 8, 25, capture(source, context, otherInside())));
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
    }

    @Test
    void reviewRetiredAndLegacyAcceptedContextsAreIneligible() {
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationContext review = contexts.insert(context(UUID.randomUUID().toString(), anchor(),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 8, null));
        assertFailureUnchanged(SourceBindingConflictException.class, source, review, 4, 8, 25,
                capture(source, review, photos()));
        LocationContext retired = contexts.insert(context(UUID.randomUUID().toString(), otherAnchor(),
                LifecycleStatus.RETIRED, ContinuityStatus.ACCEPTED, 8,
                acceptanceJson(UUID.randomUUID().toString(), 8, otherAnchor())));
        assertFailureUnchanged(SourceBindingConflictException.class, source, retired, 4, 8, 25,
                capture(source, retired, photos()));
        LocationContext legacy = contexts.insert(context(UUID.randomUUID().toString(),
                LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Legacy"),
                LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED, 8,
                new MacOsApfsLocationContextEvidenceCodec().encode(contextEvidence(
                        LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Legacy"),
                        VOLUME_UUID, "2", true, false))));
        assertFailureUnchanged(SourceBindingConflictException.class, source, legacy, 4, 8, 25,
                capture(source, legacy, photos()));
    }

    @Test
    void corruptCurrentAcceptanceEvidenceFailsClosed() {
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationPath[] anchors = {anchor(), otherAnchor(), thirdAnchor(), fourthAnchor()};
        for (int index = 0; index < anchors.length; index++) {
            String id = UUID.randomUUID().toString();
            String evidence = switch (index) {
                case 0 -> "{";
                case 1 -> acceptanceJson(UUID.randomUUID().toString(), 8, anchors[index]);
                case 2 -> acceptanceJson(id, 7, anchors[index]);
                default -> acceptanceJson(id, 8, otherAnchor());
            };
            LocationContext corrupt = contexts.insert(context(id, anchors[index], LifecycleStatus.ACTIVE,
                    ContinuityStatus.ACCEPTED, 8, evidence));
            assertFailureUnchanged(IllegalStateException.class, source, corrupt, 4, 8, 25,
                    capture(source, corrupt, photos()));
        }
    }

    @Test
    void freshContextMustMatchPersistedBaseline() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        SourceBindingCapture unavailable = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.unavailable(),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                        VOLUME_UUID, true, false)));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, unavailable);
        SourceBindingCapture changedInode = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "3", true, false)),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                        VOLUME_UUID, true, false)));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, changedInode);
    }

    @Test
    void sourceRootProvenanceAndClassificationMustMatch() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25,
                new SourceBindingCapture(source.id() + 1, source.rootPath(),
                        ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                        ContinuityProbeResult.accepted(rootEvidence(context.id(), 8, 5, photos(),
                                VOLUME_UUID, true, false))));
        assertInvalidRoot(source, context, rootEvidence(UUID.randomUUID().toString(), 8, 5,
                photos(), VOLUME_UUID, true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 7, 5,
                photos(), VOLUME_UUID, true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 4,
                photos(), VOLUME_UUID, true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                photos(), "22222222-3333-4444-5555-666666666666", true, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                photos(), VOLUME_UUID, false, false));
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                photos(), VOLUME_UUID, true, true));
        SourceBindingCapture unavailable = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.unavailable());
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, unavailable);
    }

    @Test
    void siblingRootIsRejectedByStructuralContainment() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Other/Photos");
        assertInvalidRoot(source, context, rootEvidence(context.id(), 8, 5,
                LocationPathParser.parse(LocationDialect.UNIX, source.rootPath()), VOLUME_UUID, true, false));
    }

    @Test
    void boundSourceBlocksRetirementAndReplacement() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        assertThrows(LocationContextRetirementConflictException.class,
                () -> retirement.retireActive(context.id(), 8, 30));
        LocationContext next = replacementContext(anchor());
        assertThrows(LocationContextReplacementConflictException.class,
                () -> replacement.replaceActive(context.id(), 8, 30, next));
        assertEquals(bound, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
        assertTrue(contexts.findById(next.id()).isEmpty());
    }

    @Test
    void guardedUpdateMissIsConflict() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        coordinator.forceGuardMiss = true;
        assertFailureUnchanged(SourceBindingConflictException.class, source, context, 4, 8, 25,
                capture(source, context, photos()));
    }

    @Test
    void bindingAuthorityRejectsWrongSourceIdAndRevision() {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        Source bound = bind(source, context, capture(source, context, photos()));
        SourceBindingEvidence envelope = bindingCodec.decode(bound.bindingEvidenceJson());
        assertInvalidBound(bound, new SourceBindingEvidence(1, source.id() + 1,
                envelope.macOsApfsSourceRootEvidence()));
        assertInvalidBound(bound, new SourceBindingEvidence(1, source.id(),
                rootEvidence(context.id(), 8, 4, photos(), VOLUME_UUID, true, false)));
        assertInvalidBound(bound, new SourceBindingEvidence(1, source.id(),
                rootEvidence(UUID.randomUUID().toString(), 8, 5, photos(), VOLUME_UUID, true, false)));
    }

    @Test
    void bindVersusBindAdvancesSourceOnce() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        RaceResults results = race(() -> bind(source, context, capture(source, context, photos())),
                () -> bind(source, context, capture(source, context, photos())));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof SourceBindingConflictException);
        assertEquals(5, sources.findSourceById(source.id()).orElseThrow().locationRevision());
    }

    @Test
    void bindWinsRetirement() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        RaceResults results = race(() -> bind(source, context, capture(source, context, photos())),
                () -> retirement.retireActive(context.id(), 8, 30));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof LocationContextRetirementConflictException);
        assertEquals(LifecycleStatus.ACTIVE, contexts.findById(context.id()).orElseThrow().lifecycleStatus());
    }

    @Test
    void retirementWinsBinding() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        RaceResults results = race(() -> retirement.retireActive(context.id(), 8, 30),
                () -> bind(source, context, capture(source, context, photos())));
        assertTrue(results.first() instanceof LocationContext);
        assertTrue(results.second() instanceof SourceBindingConflictException);
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
    }

    @Test
    void bindWinsReplacement() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationContext next = replacementContext(anchor());
        RaceResults results = race(() -> bind(source, context, capture(source, context, photos())),
                () -> replacement.replaceActive(context.id(), 8, 30, next));
        assertTrue(results.first() instanceof Source);
        assertTrue(results.second() instanceof LocationContextReplacementConflictException);
        assertTrue(contexts.findById(next.id()).isEmpty());
    }

    @Test
    void replacementWinsBinding() throws Exception {
        LocationContext context = acceptedContext(anchor());
        Source source = legacySource("/Volumes/Archive/Photos");
        LocationContext next = replacementContext(anchor());
        RaceResults results = race(() -> replacement.replaceActive(context.id(), 8, 30, next),
                () -> bind(source, context, capture(source, context, photos())));
        assertEquals(next, results.first());
        assertTrue(results.second() instanceof SourceBindingConflictException);
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(next, contexts.findById(next.id()).orElseThrow());
    }

    private RaceResults race(Attempt firstAttempt, Attempt secondAttempt) throws Exception {
        ReservationGate gate = new ReservationGate();
        coordinator.gate = gate;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> attempt(firstAttempt));
            try {
                assertTrue(gate.firstReserved.await(10, TimeUnit.SECONDS));
                var second = callers.submit(() -> attempt(secondAttempt));
                assertTrue(gate.secondEntered.await(10, TimeUnit.SECONDS));
                assertFalse(gate.secondReserved.await(500, TimeUnit.MILLISECONDS));
                assertEquals(1, gate.secondAuthorityRead.getCount());
                gate.releaseFirst.countDown();
                Object firstResult = first.get(15, TimeUnit.SECONDS);
                Object secondResult = second.get(15, TimeUnit.SECONDS);
                assertEquals(0, gate.secondReserved.getCount());
                assertEquals(0, gate.secondAuthorityRead.getCount());
                return new RaceResults(firstResult, secondResult);
            } finally {
                gate.releaseFirst.countDown();
                coordinator.gate = null;
            }
        }
    }

    private static Object attempt(Attempt attempt) {
        try {
            return attempt.run();
        } catch (SourceBindingConflictException | LocationContextRetirementConflictException
                | LocationContextReplacementConflictException exception) {
            return exception;
        }
    }

    private <T extends Throwable> void assertFailureUnchanged(Class<T> expected, Source source,
            LocationContext context, long sourceRevision, long contextRevision, long time, SourceBindingCapture capture) {
        assertThrows(expected, () -> binding.bindUnboundSource(
                source.id(), sourceRevision, context.id(), contextRevision, time, capture));
        assertEquals(source, sources.findSourceById(source.id()).orElseThrow());
        assertEquals(context, contexts.findById(context.id()).orElseThrow());
    }

    private void assertInvalidRoot(Source source, LocationContext context, MacOsApfsSourceRootEvidence root) {
        SourceBindingCapture capture = new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(anchor(), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.accepted(root));
        assertFailureUnchanged(IllegalArgumentException.class, source, context, 4, 8, 25, capture);
    }

    private void assertInvalidBound(Source bound, SourceBindingEvidence evidence) {
        Source altered = new Source(bound.id(), bound.name(), bound.rootPath(), bound.rootPathKey(),
                bound.locationRevision(), bound.rootPathDialect(), bound.boundLocationContextId(),
                bindingCodec.encode(evidence), bound.createdAtMs(), bound.updatedAtMs());
        assertThrows(IllegalStateException.class, () -> SourceBindingAuthority.requireCurrentBound(altered));
    }

    private Source bind(Source source, LocationContext context, SourceBindingCapture capture) {
        return binding.bindUnboundSource(source.id(), 4, context.id(), 8, 25, capture);
    }

    private SourceBindingCapture capture(Source source, LocationContext context, LocationPath root) {
        return capture(source, context, root, 5);
    }

    private SourceBindingCapture capture(Source source, LocationContext context, LocationPath root, long rootRevision) {
        return new SourceBindingCapture(source.id(), source.rootPath(),
                ContinuityProbeResult.accepted(contextEvidence(
                        pathCodec.decode(context.anchorLocationPath()), VOLUME_UUID, "2", true, false)),
                ContinuityProbeResult.accepted(rootEvidence(context.id(), context.revision(),
                        rootRevision, root, VOLUME_UUID, true, false)));
    }

    private LocationContext acceptedContext(LocationPath anchor) {
        String id = UUID.randomUUID().toString();
        return contexts.insert(context(id, anchor, LifecycleStatus.ACTIVE, ContinuityStatus.ACCEPTED,
                8, acceptanceJson(id, 8, anchor)));
    }

    private LocationContext replacementContext(LocationPath anchor) {
        return new LocationContext(UUID.randomUUID().toString(), pathCodec.encode(anchor), key(anchor),
                LifecycleStatus.ACTIVE, ContinuityStatus.REVIEW_REQUIRED, 19, null, 30, 30);
    }

    private LocationContext context(String id, LocationPath anchor, LifecycleStatus lifecycle,
            ContinuityStatus status, long revision, String evidence) {
        return new LocationContext(id, pathCodec.encode(anchor), key(anchor), lifecycle, status,
                revision, evidence, 10, 20);
    }

    private String acceptanceJson(String id, long revision, LocationPath anchor) {
        return acceptanceCodec.encode(new LocationContextAcceptanceEvidence(1, id, revision,
                contextEvidence(anchor, VOLUME_UUID, "2", true, false)));
    }

    private Source legacySource(String root) {
        return sources.insert(new Source(null, "Photos", root, root, 4, 10, 12));
    }

    private static MacOsApfsLocationContextEvidence contextEvidence(LocationPath anchor,
            String volume, String inode, boolean directory, boolean link) {
        return new MacOsApfsLocationContextEvidence(1, "macos-local-apfs", 1, anchor,
                LocationKeyCodec.encode(anchor), "apfs", volume, inode, directory, link, 99,
                MacOsApfsLocationContextEvidence.Diagnostics.empty());
    }

    private static MacOsApfsSourceRootEvidence rootEvidence(String contextId, long contextRevision,
            long sourceRevision, LocationPath root, String volume, boolean directory, boolean link) {
        return new MacOsApfsSourceRootEvidence(1, "macos-local-apfs-source-root", 1,
                contextId, contextRevision, sourceRevision, root, LocationKeyCodec.encode(root),
                volume, "123456", new MacOsApfsSourceRootEvidence.BirthTime(100, 123456789),
                directory, link, 99);
    }

    private static LocationPath anchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive");
    }

    private static LocationPath photos() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Photos");
    }

    private static LocationPath otherInside() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Other");
    }

    private static LocationPath thirdInside() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Archive/Third");
    }

    private static LocationPath otherAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Other");
    }

    private static LocationPath thirdAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Third");
    }

    private static LocationPath fourthAnchor() {
        return LocationPathParser.parse(LocationDialect.UNIX, "/Volumes/Fourth");
    }

    private static String key(LocationPath path) {
        return LocationKeyCodec.encode(path).value();
    }

    private static final String VOLUME_UUID = "11111111-2222-3333-4444-555555555555";

    @FunctionalInterface
    private interface Attempt {
        Object run();
    }

    private record RaceResults(Object first, Object second) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReservationHooks {
        @Bean
        ReservationCoordinator reservationCoordinator() {
            return new ReservationCoordinator();
        }

        @Bean
        @Primary
        CoordinatedLocationContextRepository coordinatedLocationContextRepository(
                JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            return new CoordinatedLocationContextRepository(jdbc, coordinator);
        }

        @Bean
        @Primary
        CoordinatedCatalogRepository coordinatedCatalogRepository(JdbcTemplate jdbc,
                ReservationCoordinator coordinator) {
            return new CoordinatedCatalogRepository(jdbc, coordinator);
        }
    }

    static class ReservationCoordinator {
        volatile ReservationGate gate;
        volatile boolean forceGuardMiss;
    }

    static class CoordinatedLocationContextRepository extends LocationContextRepository {
        private final ReservationCoordinator coordinator;

        CoordinatedLocationContextRepository(JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            super(jdbc);
            this.coordinator = coordinator;
        }

        @Override
        public void reserveWrite() {
            ReservationGate gate = coordinator.gate;
            if (gate == null) {
                super.reserveWrite();
                return;
            }
            if (gate.firstCaller.compareAndSet(null, Thread.currentThread())) {
                super.reserveWrite();
                gate.firstReserved.countDown();
                try {
                    if (!gate.releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("First writer was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding first writer", exception);
                }
            } else {
                gate.secondCaller.set(Thread.currentThread());
                gate.secondEntered.countDown();
                super.reserveWrite();
                gate.secondReserved.countDown();
            }
        }

        @Override
        public Optional<LocationContext> findById(String id) {
            markAuthorityRead(coordinator.gate);
            return super.findById(id);
        }
    }

    static class CoordinatedCatalogRepository extends CatalogRepository {
        private final ReservationCoordinator coordinator;

        CoordinatedCatalogRepository(JdbcTemplate jdbc, ReservationCoordinator coordinator) {
            super(jdbc);
            this.coordinator = coordinator;
        }

        @Override
        public Optional<Source> findSourceById(long id) {
            markAuthorityRead(coordinator.gate);
            return super.findSourceById(id);
        }

        @Override
        public int bindUnboundSource(long sourceId, long revision, String configuredRootPath,
                String rootLocationKey, String contextId, String evidenceJson, long boundAtMs) {
            if (coordinator.forceGuardMiss) {
                return 0;
            }
            return super.bindUnboundSource(sourceId, revision, configuredRootPath,
                    rootLocationKey, contextId, evidenceJson, boundAtMs);
        }
    }

    private static void markAuthorityRead(ReservationGate gate) {
        if (gate != null && Thread.currentThread() == gate.secondCaller.get()) {
            gate.secondAuthorityRead.countDown();
        }
    }

    static class ReservationGate {
        final AtomicReference<Thread> firstCaller = new AtomicReference<>();
        final AtomicReference<Thread> secondCaller = new AtomicReference<>();
        final CountDownLatch firstReserved = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch secondReserved = new CountDownLatch(1);
        final CountDownLatch secondAuthorityRead = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
    }
}
