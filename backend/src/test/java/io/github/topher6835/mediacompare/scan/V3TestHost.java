package io.github.topher6835.mediacompare.scan;

import java.nio.file.Path;
import java.util.UUID;

import io.github.topher6835.mediacompare.catalog.CatalogRepository;
import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceAuthority;
import io.github.topher6835.mediacompare.catalog.LocationContextRepository;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingAuthority;
import io.github.topher6835.mediacompare.location.ContinuityProbeResult;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidence;
import io.github.topher6835.mediacompare.location.LocationContextAcceptanceEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationKeyCodec;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import io.github.topher6835.mediacompare.location.LocationPathParser;
import io.github.topher6835.mediacompare.location.MacOsApfsLocationContextEvidence;
import io.github.topher6835.mediacompare.location.MacOsApfsMountInspector.MountObservation;
import io.github.topher6835.mediacompare.location.MacOsApfsSourceRootEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidence;
import io.github.topher6835.mediacompare.location.SourceBindingEvidenceCodec;
import io.github.topher6835.mediacompare.scan.authority.ScanObservationAuthority;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deterministic APFS observations for v3 API tests; no host filesystem probe is run. */
public final class V3TestHost {
    private static final String VOLUME_ID = "11111111-2222-3333-4444-555555555555";

    private V3TestHost() {
    }

    public static Source boundSource(CatalogRepository catalog, JdbcTemplate jdbc,
            Path root, String name) throws Exception {
        Path exactRoot = root.toRealPath();
        var location = LocationPathParser.parse(LocationDialect.UNIX, exactRoot.toString());
        Source source = catalog.insert(new Source(null, name,
                exactRoot.toString(), exactRoot.toString(), 0, 1, 1));
        String contextId = UUID.randomUUID().toString();
        var contextEvidence = new MacOsApfsLocationContextEvidence(1,
                MacOsApfsLocationContextEvidence.PROFILE, 1,
                location, LocationKeyCodec.encode(location), "apfs", VOLUME_ID, "2",
                true, false, 1, MacOsApfsLocationContextEvidence.Diagnostics.empty());
        String acceptance = new LocationContextAcceptanceEvidenceCodec().encode(
                new LocationContextAcceptanceEvidence(1, contextId, 1, contextEvidence));
        jdbc.update("""
                INSERT INTO location_context (id, anchor_location_path, anchor_location_key,
                    lifecycle_status, continuity_status, revision, continuity_evidence_json,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, 'ACTIVE', 'ACCEPTED', 1, ?, 1, 1)
                """, contextId, new LocationPathCodec().encode(location),
                LocationKeyCodec.encode(location).value(), acceptance);
        var rootEvidence = new MacOsApfsSourceRootEvidence(1,
                MacOsApfsSourceRootEvidence.PROFILE, 1, contextId, 1, 1,
                location, LocationKeyCodec.encode(location), VOLUME_ID, "10",
                new MacOsApfsSourceRootEvidence.BirthTime(100, 200), true, false, 1);
        String binding = new SourceBindingEvidenceCodec().encode(
                new SourceBindingEvidence(1, source.id(), rootEvidence));
        jdbc.update("""
                UPDATE source SET root_path_key = ?, root_path_dialect = 'unix',
                    bound_location_context_id = ?, binding_evidence_json = ?, location_revision = 1
                WHERE id = ?
                """, LocationKeyCodec.encode(location).value(), contextId, binding, source.id());
        return catalog.findSourceById(source.id()).orElseThrow();
    }

    public static Version3AuthorityCapture trustedCapture(
            CatalogRepository catalog, LocationContextRepository contexts) {
        return new Version3AuthorityCapture(catalog, contexts, null) {
            @Override
            public io.github.topher6835.mediacompare.scan.authority.ScanAuthorityResult<
                    io.github.topher6835.mediacompare.scan.authority.ScanAuthoritySnapshot>
                    capture(long sourceId) {
                var source = catalog.findSourceById(sourceId).orElseThrow();
                var context = contexts.findById(source.boundLocationContextId()).orElseThrow();
                var baseline = LocationContextAcceptanceAuthority.requireCurrentAccepted(context)
                        .macOsApfsEvidence();
                var root = SourceBindingAuthority.requireCurrentBound(source)
                        .macOsApfsSourceRootEvidence();
                var freshContext = new MacOsApfsLocationContextEvidence(1,
                        MacOsApfsLocationContextEvidence.PROFILE, 1,
                        baseline.anchorLocationPath(), baseline.anchorLocationKey(),
                        baseline.fileSystemType(), baseline.volumeUuid(), baseline.anchorInode(),
                        true, false, 2, baseline.diagnostics());
                var freshRoot = new MacOsApfsSourceRootEvidence(1,
                        MacOsApfsSourceRootEvidence.PROFILE, 1,
                        root.locationContextId(), root.locationContextRevision(),
                        root.sourceLocationRevision(), root.rootLocationPath(), root.rootLocationKey(),
                        root.volumeUuid(), root.rootInode(), root.rootBirthTime(), true, false, 2);
                return ScanObservationAuthority.capture(source, context,
                        ContinuityProbeResult.accepted(freshContext),
                        ContinuityProbeResult.accepted(freshRoot));
            }
        };
    }

    public static Version3DiscoveryWalker trustedWalker() {
        var mount = new MountObservation("/dev/disk2s1", "/", "apfs", VOLUME_ID);
        return new Version3DiscoveryWalker(ignored -> ContinuityProbeResult.accepted(mount));
    }
}
