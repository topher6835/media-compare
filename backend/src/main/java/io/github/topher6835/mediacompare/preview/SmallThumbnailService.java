package io.github.topher6835.mediacompare.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import io.github.topher6835.mediacompare.analysis.MediaMetadataCandidateRepository;
import io.github.topher6835.mediacompare.analysis.MediaMetadataFileEvidenceValidator;
import org.springframework.stereotype.Service;

import static io.github.topher6835.mediacompare.preview.ThumbnailGenerationResult.Outcome.*;

/** Synchronous Java-only entry point. One bounded process-local lock, no jobs or worker threads. */
@Service
public class SmallThumbnailService {
    private final MediaMetadataCandidateRepository candidates;
    private final MediaMetadataFileEvidenceValidator evidenceValidator;
    private final PreviewAssetRepository assets;
    private final SmallThumbnailRenderer renderer;
    private final PreviewCacheWriter cache;
    private final ThumbnailPublisher publisher;

    public SmallThumbnailService(MediaMetadataCandidateRepository candidates,
            MediaMetadataFileEvidenceValidator evidenceValidator, PreviewAssetRepository assets,
            SmallThumbnailRenderer renderer, PreviewCacheWriter cache, ThumbnailPublisher publisher) {
        this.candidates = candidates;
        this.evidenceValidator = evidenceValidator;
        this.assets = assets;
        this.renderer = renderer;
        this.cache = cache;
        this.publisher = publisher;
    }

    public synchronized ThumbnailGenerationResult generate(long fileEntryId) {
        var candidate = candidates.findCurrentOccurrence(fileEntryId).orElseThrow(
                () -> new ThumbnailGenerationException("FileEntry has no current trusted occurrence"));
        publisher.requireCurrent(candidate);
        var definition = SmallThumbnailDefinition.definition();
        var evidence = ThumbnailPublisher.evidence(candidate);
        String key = PreviewAssetKey.compute(evidence, PreviewKind.SMALL_THUMBNAIL, definition);
        String relativePath = PreviewCacheLayout.relativePath(PreviewKind.SMALL_THUMBNAIL, key,
                SmallThumbnailDefinition.EXTENSION);
        var existing = assets.findByAssetKey(key);
        Path temporary = null;
        try {
            if (existing.isPresent()) {
                try {
                    cache.validatePublished(existing.get());
                    return new ThumbnailGenerationResult(REUSED,
                            publisher.publishIfStillCurrent(candidate, existing.get()));
                } catch (NoSuchFileException missingCacheFile) {
                    // Recreate only missing files, never inconsistent/unsafe immutable files.
                }
            }
            var before = evidenceValidator.captureBeforeExtraction(candidate);
            Path source = before.path();
            temporary = cache.createTemporary(relativePath, key);
            var rendered = renderer.render(source, temporary);
            evidenceValidator.validateAfterExtraction(candidate, before);
            if (rendered.isEmpty()) {
                publisher.requireCurrent(candidate);
                return new ThumbnailGenerationResult(UNSUPPORTED, null);
            }
            var dimensions = rendered.get();
            long bytes = cache.validateTemporary(temporary, dimensions);
            var generated = new PreviewAsset(null, key, evidence, PreviewKind.SMALL_THUMBNAIL, definition,
                    relativePath, SmallThumbnailDefinition.MEDIA_TYPE, dimensions.width(), dimensions.height(),
                    bytes, System.currentTimeMillis());
            if (existing.isPresent()) {
                ThumbnailPublisher.requireEquivalent(existing.get(), generated);
            }
            boolean publishedFile = cache.publish(temporary, generated);
            PreviewAsset published = publisher.publishIfStillCurrent(candidate, generated);
            return new ThumbnailGenerationResult(publishedFile ? GENERATED : REUSED, published);
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            throw new ThumbnailGenerationException("Small thumbnail rendering/publication failed", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // Disposable cache leftovers may be removed by a future cleanup policy.
                }
            }
        }
    }
}
