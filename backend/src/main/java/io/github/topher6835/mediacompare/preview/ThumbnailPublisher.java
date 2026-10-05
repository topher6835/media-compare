package io.github.topher6835.mediacompare.preview;

import io.github.topher6835.mediacompare.contentread.ExfatContentReadCapture;
import io.github.topher6835.mediacompare.contentread.ExfatContentReadCatalog;
import io.github.topher6835.mediacompare.contentread.ExfatProtectedOriginalAccess;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;

import java.io.IOException;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataFileCandidate;
import io.github.topher6835.mediacompare.analysis.StaleMediaMetadataEvidenceException;
import io.github.topher6835.mediacompare.catalog.CurrentMembershipAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ThumbnailPublisher {
    private final MediaMetadataCandidateRepository candidates;
    private final CurrentMembershipAuthority authority;
    private final PreviewAssetRepository assets;
    private final PreviewCacheWriter cache;

    public ThumbnailPublisher(MediaMetadataCandidateRepository candidates, CurrentMembershipAuthority authority,
            PreviewAssetRepository assets, PreviewCacheWriter cache) {
        this.candidates = candidates;
        this.authority = authority;
        this.assets = assets;
        this.cache = cache;
    }

    /** Short transaction before original access; also used for an unsupported result's final guard. */
    @Transactional
    public void requireCurrent(MediaMetadataFileCandidate candidate) {
        guard(candidate);
    }

    /** The usable immutable final file must precede successful SQLite metadata. */
    @Transactional(rollbackFor = IOException.class)
    public PreviewAsset publishIfStillCurrent(MediaMetadataFileCandidate candidate, PreviewAsset generated)
            throws IOException {
        guard(candidate);
        if (!generated.evidence().equals(evidence(candidate))) {
            throw new IllegalArgumentException("Preview does not describe the guarded source");
        }
        cache.validatePublished(generated);
        var existing = assets.findByAssetKey(generated.assetKey());
        if (existing.isPresent()) {
            requireEquivalent(existing.get(), generated);
            return existing.get();
        }
        // The authority guard reserves the SQLite writer before lookup/insert, serializing DB races.
        return assets.insert(generated);
    }

    private ExfatContentReadCatalog contentCatalog;
    private ExfatAuthorityWindowRegistry contentRegistry;
    @org.springframework.beans.factory.annotation.Autowired
    void contentGuard(ExfatContentReadCatalog catalog,
            ExfatAuthorityWindowRegistry registry) {
        contentCatalog = catalog; contentRegistry = registry;
    }

    public PreviewAsset publishProtected(ExfatContentReadCapture capture,
            ExfatProtectedOriginalAccess.VerifiedRead proof,
            PreviewAsset generated, PreviewCacheWriter.PreparedPublication prepared) throws IOException {
        proof.require(capture);
        try (var held = contentRegistry.requireContent(capture.authority())) { contentCatalog.require(capture, true); }
        var file = capture.file();
        var expected = new PreviewSourceEvidence(file.id(), capture.content().id(), file.observationRevision(),
                file.sizeBytes(), file.modifiedTimeEpochSecond(), file.modifiedTimeNano());
        if (!expected.equals(generated.evidence())) throw new IllegalArgumentException("Protected preview evidence disagrees");
        var existing = assets.findByAssetKey(generated.assetKey());
        if (existing.isPresent()) requireEquivalent(existing.get(), generated);
        prepared.install();
        return existing.orElseGet(() -> assets.insert(generated));
    }

    private void guard(MediaMetadataFileCandidate candidate) {
        authority.reserveAndRequire(candidate.sourceId(), candidate.sourceLocationRevision(),
                candidate.contextId(), candidate.contextRevision(), candidate.fileEntryId(), candidate.membershipId());
        if (candidates.verifyCandidate(candidate) != 1) {
            throw new StaleMediaMetadataEvidenceException(candidate.fileEntryId(), "catalog evidence changed");
        }
    }

    static PreviewSourceEvidence evidence(MediaMetadataFileCandidate candidate) {
        return new PreviewSourceEvidence(candidate.fileEntryId(), candidate.contentRecordId(),
                candidate.observationRevision(), candidate.expectedSizeBytes(),
                candidate.expectedModifiedTimeEpochSecond(), candidate.expectedModifiedTimeNano());
    }

    static void requireEquivalent(PreviewAsset existing, PreviewAsset generated) {
        if (!existing.assetKey().equals(generated.assetKey()) || !existing.evidence().equals(generated.evidence())
                || existing.kind() != generated.kind() || !existing.definition().equals(generated.definition())
                || !existing.relativePath().equals(generated.relativePath())
                || !existing.mediaType().equals(generated.mediaType())
                || existing.pixelWidth() != generated.pixelWidth() || existing.pixelHeight() != generated.pixelHeight()
                || existing.assetSizeBytes() != generated.assetSizeBytes()) {
            throw new ThumbnailGenerationException("Existing preview metadata is not equivalent");
        }
    }
}
